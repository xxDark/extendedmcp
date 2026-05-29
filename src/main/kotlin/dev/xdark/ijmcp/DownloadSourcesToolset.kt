@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.AttachSourcesProvider
import com.intellij.codeInsight.AttachSourcesProviderFilter
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.util.GradleConstants

class DownloadSourcesToolset : McpToolset {

	companion object {
		private val ATTACH_SOURCES_EP =
			ExtensionPointName<AttachSourcesProvider>("com.intellij.attachSourcesProvider")
	}

	@McpTool
	@McpDescription(
		"""
        |Downloads/attaches source code for a library class.
        |Given a fully qualified class name, finds the library it belongs to and attempts to download sources.
        |Tries all registered source providers (Maven, Kotlin decompiler, etc.).
        |After a successful download, triggers a Gradle sync to wire sources into the project model.
        |If no provider can handle it (e.g. Gradle-managed libraries), suggests using gradle_sync(download_sources=true).
    """
	)
	suspend fun download_sources(
		@McpDescription("Fully qualified class name (e.g. 'com.intellij.openapi.project.Project')") class_name: String,
		@McpDescription("Timeout in milliseconds for download (default 60000)") timeout: Int = 60000,
	): Any {
		val project = currentCoroutineContext().project

		val (virtualFile, libraryName) = readAction {
			val cls = JavaPsiFacade.getInstance(project)
				.findClass(class_name, GlobalSearchScope.allScope(project))
				?: mcpFail("Class not found: $class_name")

			val vf = cls.containingFile?.virtualFile
				?: mcpFail("No virtual file for class: $class_name")

			val entries = ProjectFileIndex.getInstance(project)
				.getOrderEntriesForFile(vf)
				.filterIsInstance<LibraryOrderEntry>()

			if (entries.isEmpty()) {
				mcpFail("$class_name is not in a library (might be project source)")
			}

			val lib = entries.first().library
			if (lib != null && lib.getFiles(OrderRootType.SOURCES).isNotEmpty()) {
				mcpFail("Sources already available for ${lib.name ?: class_name}")
			}

			vf to (lib?.name ?: "unknown library")
		}

		val (psiFile, orderEntries) = readAction {
			val psi = PsiManager.getInstance(project).findFile(virtualFile)
				?: mcpFail("Cannot get PsiFile for $class_name")
			val entries = ProjectFileIndex.getInstance(project)
				.getOrderEntriesForFile(virtualFile)
				.filterIsInstance<LibraryOrderEntry>()
			psi to entries
		}

		val actions = readAction {
			collectActions(orderEntries, psiFile)
		}

		if (actions.isNotEmpty()) {
			val action = actions.first()
			val downloadDeferred = CompletableDeferred<Boolean>()

			withContext(Dispatchers.EDT) {
				val callback = action.perform(orderEntries)
				callback.doWhenDone(Runnable { downloadDeferred.complete(true) })
				callback.doWhenRejected(Runnable { downloadDeferred.complete(false) })
			}

			val downloadResult = withTimeoutOrNull(timeout.toLong()) {
				downloadDeferred.await()
			}

			if (downloadResult != true) {
				return when (downloadResult) {
					false -> "Download failed for $libraryName via '${action.name}'"
					else -> "Download timed out after ${timeout}ms for $libraryName"
				}
			}

			val syncResult = gradleSync(project, timeout.toLong())
			return buildString {
				append("Sources downloaded for ").append(libraryName).append(" via '").append(action.name).append("'\n")
				append("Gradle sync: ").append(syncResult)
			}
		}

		return buildString {
			append("No source download provider available for ").appendLine(libraryName)
			append("This is common for Gradle-managed libraries where sources must be fetched during sync.\n")
			append("Use: gradle_sync(download_sources=true)")
		}
	}

	private suspend fun gradleSync(project: com.intellij.openapi.project.Project, timeout: Long): String {
		val spec = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
		val syncDeferred = CompletableDeferred<Boolean>()
		spec.withCallback { success -> syncDeferred.complete(success) }
		ExternalSystemUtil.refreshProjects(spec)

		val result = withTimeoutOrNull(timeout) {
			syncDeferred.await()
		}
		return when (result) {
			true -> "completed successfully"
			false -> "completed with errors"
			null -> "timed out"
		}
	}

	private fun collectActions(
		orderEntries: List<LibraryOrderEntry>,
		psiFile: PsiFile,
	): List<AttachSourcesProvider.AttachSourcesAction> {
		val allActions = mutableListOf<AttachSourcesProvider.AttachSourcesAction>()
		for (provider in ATTACH_SOURCES_EP.extensionList) {
			if (!AttachSourcesProviderFilter.isProviderApplicable(provider, orderEntries, psiFile)) continue
			if (!provider.isApplicable(orderEntries, psiFile)) continue
			allActions.addAll(provider.getActions(orderEntries, psiFile))
		}
		return allActions
	}
}
