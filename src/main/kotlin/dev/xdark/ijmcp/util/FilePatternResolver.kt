package dev.xdark.ijmcp.util

import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

data class ResolvedFileEntry(
	val relativePath: String,
	val virtualFile: VirtualFile,
)

data class PsiFileEntry(
	val relativePath: String,
	val psiFile: PsiFile,
	val document: Document,
)

data class FilePatternResult(
	val files: List<ResolvedFileEntry>,
	val hitMax: Boolean,
	val missingPaths: Int,
)

suspend fun List<ResolvedFileEntry>.resolvePsi(project: Project): List<PsiFileEntry> = readAction {
	val psiManager = PsiManager.getInstance(project)
	val fdm = FileDocumentManager.getInstance()
	val result = mapNotNull { entry ->
		val psiFile = psiManager.findFile(entry.virtualFile) ?: return@mapNotNull null
		val document = fdm.getDocument(entry.virtualFile) ?: return@mapNotNull null
		PsiFileEntry(entry.relativePath, psiFile, document)
	}
	if (result.isEmpty() && size == 1) {
		mcpFail("Cannot resolve file: ${first().relativePath}")
	}
	result
}

suspend fun FilePatternResult.resolvePsi(project: Project): List<PsiFileEntry> =
	files.resolvePsi(project)

suspend fun resolveFilesByPattern(
	project: Project,
	pattern: String,
	extensions: Set<String>? = null,
	max_files: Int = 500,
): FilePatternResult {
	if (!isGlob(pattern)) {
		return resolveLiteralPath(project, pattern, extensions)
	}
	return resolveGlobPattern(project, pattern, extensions, max_files)
}

fun isGlob(pattern: String) = pattern.any { it in "*?[{" }

private suspend fun resolveLiteralPath(
	project: Project,
	path: String,
	extensions: Set<String>?,
): FilePatternResult {
	val resolvedPath = project.resolveInProject(path)
	val vf = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
		?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
		?: mcpFail("File not found: $path")

	if (extensions != null && vf.extension !in extensions) {
		mcpFail("File '$path' is not a supported file type (expected: ${extensions.joinToString(", ") { "*.$it" }})")
	}

	val relativePath = readAction {
		try {
			project.projectDirectory.relativizeIfPossible(vf)
		} catch (_: IllegalArgumentException) {
			path
		}
	}

	return FilePatternResult(
		files = listOf(ResolvedFileEntry(relativePath, vf)),
		hitMax = false,
		missingPaths = 0,
	)
}

private suspend fun resolveGlobPattern(
	project: Project,
	pattern: String,
	extensions: Set<String>?,
	max_files: Int,
): FilePatternResult {
	val matchers = createGlobMatchers(pattern)

	return readAction {
		val projectDir = project.projectDirectory
		val result = mutableListOf<ResolvedFileEntry>()
		var hitMax = false

		ProjectFileIndex.getInstance(project).iterateContent { vf ->
			if (!vf.isDirectory && vf.isValid) {
				if (extensions == null || vf.extension in extensions) {
					val rel = try {
						projectDir.relativizeIfPossible(vf)
					} catch (_: IllegalArgumentException) {
						return@iterateContent true
					}
					if (rel.startsWith("..") || rel.contains(".jar!")) {
						return@iterateContent true
					}
					if (matchers.any { it.matches(Path.of(rel)) }) {
						result.add(ResolvedFileEntry(rel, vf))
						if (result.size >= max_files) {
							hitMax = true
							return@iterateContent false
						}
					}
				}
			}
			true
		}

		FilePatternResult(result, hitMax, 0)
	}
}

// JDK's PathMatcher treats /**/ as /.*/  in regex, which requires at least
// one directory level. Expand each /**/ into a variant with just / so that
// ** can match zero directories.
private fun expandDoubleStarVariants(pattern: String): List<String> {
	val idx = pattern.indexOf("/**/")
	if (idx < 0) return listOf(pattern)
	val prefix = pattern.substring(0, idx)
	val rest = pattern.substring(idx + 4)
	return expandDoubleStarVariants(rest).flatMap { expanded ->
		listOf("$prefix/**/$expanded", "$prefix/$expanded")
	}
}

private fun createGlobMatchers(pattern: String): List<PathMatcher> {
	val fs = FileSystems.getDefault()
	return expandDoubleStarVariants(pattern).map {
		try {
			fs.getPathMatcher("glob:$it")
		} catch (e: Exception) {
			mcpFail("Invalid glob pattern '$pattern': ${e.message}")
		}
	}
}
