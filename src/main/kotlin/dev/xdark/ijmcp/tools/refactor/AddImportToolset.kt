@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.refactor

import com.intellij.lang.java.JavaLanguage
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import dev.xdark.ijmcp.util.ResolvedFile
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

class AddImportToolset : McpToolset {

	data class AddImportResult(
		val added: Boolean,
		val import: String,
		val message: String,
	)

	@McpTool
	@McpDescription(
		"""
        |Adds one or more import statements to a Java or Kotlin file.
        |Use static=true for Java static imports, allUnder=true for wildcard imports (.*).
        |Skips imports that already exist.
    """
	)
	suspend fun add_imports(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("Fully qualified names to import") fqNames: List<String>,
		@McpDescription("For Java: static import (default false)") static: Boolean = false,
		@McpDescription("Wildcard import .* (default false)") allUnder: Boolean = false,
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, filePath)
		val isKotlin = resolved.psiFile is KtFile

		val results = fqNames.filter { it.isNotBlank() }.map { fqName ->
			val trimmed = fqName.trim()
			if (isKotlin) {
				addKotlinImport(resolved, trimmed, allUnder)
			} else {
				addJavaImport(resolved, trimmed, static)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		val added = results.filter { it.added }
		val skipped = results.filter { !it.added }
		return buildString {
			if (added.size == 1) {
				append("Added import ").append(added[0].import)
			} else if (added.size > 1) {
				append("Added ").append(added.size).append(" imports:\n")
				added.forEach { append("  ").append(it.import).append('\n') }
			}
			if (skipped.isNotEmpty()) {
				if (added.isNotEmpty()) append("\n")
				skipped.forEach { append(it.import).append(": ").append(it.message).append('\n') }
			}
			if (added.isEmpty() && skipped.isEmpty()) {
				append("No imports to add")
			}
		}.trimEnd()
	}

	private suspend fun addJavaImport(
		resolved: ResolvedFile,
		fqName: String,
		isStatic: Boolean,
	): AddImportResult {
		val project = resolved.psiFile.project

		val javaFile = resolved.psiFile as? PsiJavaFile
			?: mcpFail("File is not a Java file")

		if (isStatic) {
			val lastDot = fqName.lastIndexOf('.')
			if (lastDot < 0) mcpFail("Static import requires 'ClassName.memberName' format, got: $fqName")
			val className = fqName.substring(0, lastDot)
			val memberName = fqName.substring(lastDot + 1)

			// Check if already imported
			val alreadyImported = smartReadAction(project) {
				javaFile.importList?.importStaticStatements?.any {
					it.resolveTargetClass()?.qualifiedName == className && it.referenceName == memberName
				} ?: false
			}
			if (alreadyImported) {
				return AddImportResult(added = false, import = fqName, message = "Static import already exists")
			}

			withContext(Dispatchers.EDT) {
				WriteCommandAction.runWriteCommandAction(project) {
					val factory = PsiElementFactory.getInstance(project)
					val importStatement = factory.createImportStaticStatementFromText(className, memberName)
					javaFile.importList?.add(importStatement)
				}
			}
		} else {
			// Check if already imported (single or on-demand covering it)
			val alreadyImported = readAction {
				val importList = javaFile.importList ?: return@readAction false
				importList.importStatements.any { it.qualifiedName == fqName } ||
						importList.importStatements.any {
							it.isOnDemand && it.qualifiedName == fqName.substringBeforeLast(
								'.'
							)
						}
			}
			if (alreadyImported) {
				return AddImportResult(added = false, import = fqName, message = "Import already exists")
			}

			// Direct PSI manipulation — bypasses ImportFilter which can silently skip imports
			val psiClass = smartReadAction(project) {
				JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.allScope(project))
			}
			withContext(Dispatchers.EDT) {
				WriteCommandAction.runWriteCommandAction(project) {
					val factory = PsiElementFactory.getInstance(project)
					val importStatement = if (psiClass != null) {
						factory.createImportStatement(psiClass)
					} else {
						// Class not in index — parse from text
						val dummyFile = PsiFileFactory.getInstance(project).createFileFromText(
							"_Dummy_.java",
							JavaLanguage.INSTANCE,
							"import $fqName;\nclass _Dummy_ {}"
						) as? PsiJavaFile
						dummyFile?.importList?.importStatements?.firstOrNull()
							?: mcpFail("Failed to create import for: $fqName")
					}
					javaFile.importList?.add(importStatement)
				}
			}
		}

		return AddImportResult(added = true, import = fqName, message = "Import added")
	}

	private suspend fun addKotlinImport(
		resolved: ResolvedFile,
		fqName: String,
		isAllUnder: Boolean,
	): AddImportResult {
		val project = resolved.psiFile.project

		val ktFile = resolved.psiFile as? KtFile
			?: mcpFail("File is not a Kotlin file")

		// Check if already imported
		val alreadyImported = readAction {
			ktFile.importDirectives.any { directive ->
				val path = directive.importPath ?: return@any false
				path.fqName.asString() == fqName && path.isAllUnder == isAllUnder
			}
		}
		if (alreadyImported) {
			return AddImportResult(added = false, import = fqName, message = "Import already exists")
		}

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = KtPsiFactory(project, markGenerated = true)
				val importPath = ImportPath(FqName(fqName), isAllUnder)
				val importDirective = factory.createImportDirective(importPath)

				val importList = ktFile.importList
				if (importList != null) {
					importList.add(importDirective)
				} else {
					// No import list yet — add after package directive or at beginning
					val packageDirective = ktFile.packageDirective
					if (packageDirective != null) {
						val newline = factory.createNewLine(2)
						ktFile.addAfter(importDirective, ktFile.addAfter(newline, packageDirective))
					} else {
						ktFile.addBefore(importDirective, ktFile.firstChild)
					}
				}
			}
		}

		val displayName = if (isAllUnder) "$fqName.*" else fqName
		return AddImportResult(added = true, import = displayName, message = "Import added")
	}
}
