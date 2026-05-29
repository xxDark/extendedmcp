@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.xdark.ijmcp.util.ResolvedFile
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveFilesByPattern
import dev.xdark.ijmcp.util.resolvePsi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

class DocumentationToolset : McpToolset {

    data class AddDocResult(
        val success: Boolean,
        val targetName: String,
        val replaced: Boolean,
        val message: String,
    )

    data class DocEntry(
        val name: String,
        val signature: String,
        val documentation: String?,
    )

    data class UndocumentedElement(
        val kind: String,
        val name: String,
        val line: Int,
        val signature: String,
        val class_name: String = "",
    )

    data class FileMissingDocs(
        val file_path: String,
        val undocumented: List<UndocumentedElement>,
        val total: Int,
        val documented: Int,
    )

    @McpTool
    @McpDescription(
        """
        |Adds or replaces documentation on a class, method, or field.
        |
        |Provide the documentation content as plain markdown text (no comment delimiters).
        |The tool will format it as /// markdown doc comments for Java, or /** */ KDoc for Kotlin.
        |
        |Example documentation parameter:
        |  "Returns a greeting for the given user.\n\n@param name the user's name\n@return the greeting string"
        |
        |To document the class itself, omit member_name.
        |To document a method or field, specify member_name.
        |If the target already has a doc comment, it will be replaced.
        |
        |If there are overloaded methods with the same name, the tool will return an error
        |listing each overload with its index and parameter types. Re-call with member_index
        |to pick the correct overload.
    """
    )
    suspend fun add_documentation(
        @McpDescription("Path relative to the project root") file_path: String,
        @McpDescription("Documentation content as plain markdown (no /// or /** */ delimiters)") documentation: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") class_name: String = "",
        @McpDescription("Name of the method or field to document (omit to document the class itself)") member_name: String = "",
        @McpDescription("Index of the overloaded method to document (from the overload list error)") member_index: Int = -1,
    ): Any {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, file_path)

        val isKotlin = resolved.psiFile is KtFile

        val result = if (isKotlin) {
            addKotlinDoc(resolved, documentation, class_name, member_name, member_index)
        } else {
            addJavaDoc(resolved, documentation, class_name, member_name, member_index)
        }
        return result.message
    }

    @McpTool
    @McpDescription(
        """
        |Retrieves documentation from a class, method, or field.
        |
        |Returns the raw doc comment text for the target element.
        |For overloaded methods, returns documentation for all overloads unless member_index is specified.
        |
        |Lookup by either:
        |  - qualified_class_name: fully qualified class name (e.g. "java.util.concurrent.locks.Lock")
        |    Works for any class in the project or libraries.
        |  - file_path + class_name: for project files, same as add_documentation.
        |
        |To get docs for the class itself, omit member_name.
        |To get docs for a method or field, specify member_name.
    """
    )
    suspend fun get_documentation(
        @McpDescription("Fully qualified class name (e.g. java.util.List). Use this OR file_path.") qualified_class_name: String = "",
        @McpDescription("Path relative to the project root. Use this OR qualified_class_name.") file_path: String = "",
        @McpDescription("Simple name of the target class (only with file_path)") class_name: String = "",
        @McpDescription("Name of the method or field (omit to get class docs)") member_name: String = "",
        @McpDescription("Index of a specific overloaded method") member_index: Int = -1,
    ): Any {
        val project = currentCoroutineContext().project

        val entries = if (qualified_class_name.isNotEmpty()) {
            getDocByQualifiedName(project, qualified_class_name, member_name, member_index)
        } else {
            if (file_path.isEmpty()) {
                mcpFail("Provide either qualified_class_name or file_path")
            }
            val resolved = resolveFile(project, file_path)
            val isKotlin = resolved.psiFile is KtFile
            if (isKotlin) {
                getKotlinDoc(resolved, class_name, member_name, member_index)
            } else {
                getJavaDoc(resolved, class_name, member_name, member_index)
            }
        }

        return formatDocEntries(entries)
    }

    private fun formatDocEntries(entries: List<DocEntry>): String = buildString {
        for ((i, entry) in entries.withIndex()) {
            if (i > 0) {
                appendLine()
                appendLine()
            }
            append("Documentation for ").append(entry.signature).appendLine(":")
            appendLine()
            val doc = entry.documentation
            if (doc != null) {
                append(doc)
            } else {
                append("(no documentation)")
            }
        }
    }.trimEnd()

    @McpTool
    @McpDescription(
        """
        |Reports classes, methods, fields, and top-level declarations missing documentation.
        |
        |Returns undocumented elements with kind, name, line number, and signature.
        |By default only non-private elements are reported.
        |
        |file_path can be a literal path or a glob pattern (e.g. "src/**/*.java").
        |Files with no undocumented elements are omitted from the results.
    """
    )
    suspend fun missing_documentation(
        @McpDescription("Path relative to the project root, or glob pattern (e.g. 'src/**/*.java')") file_path: String,
        @McpDescription("Include private members (default false)") include_private: Boolean = false,
    ): Any {
        val project = currentCoroutineContext().project
        val resolved = resolveFilesByPattern(project, file_path, extensions = setOf("java", "kt"))

        if (resolved.files.isEmpty()) {
            return "No matching files found"
        }

        val psiFiles = resolved.resolvePsi(project)
        val allFileResults = mutableListOf<FileMissingDocs>()

        for (entry in psiFiles) {
            val resolvedFile = ResolvedFile(entry.psiFile.virtualFile, entry.psiFile, entry.document)
            val isKotlin = entry.psiFile is KtFile

            val fileResult = if (isKotlin) {
                findMissingKotlinDocs(resolvedFile, entry.relativePath, include_private)
            } else {
                findMissingJavaDocs(resolvedFile, entry.relativePath, include_private)
            }

            if (fileResult.undocumented.isNotEmpty()) {
                allFileResults.add(fileResult)
            }
        }

        val totalUndocumented = allFileResults.sumOf { it.undocumented.size }
        if (totalUndocumented == 0) {
            return "All elements are documented (${psiFiles.size} files checked)"
        }

        return buildString {
            for (fileResult in allFileResults) {
                append(fileResult.undocumented.size).append(" undocumented elements in ").append(fileResult.file_path)
                    .append(" (").append(fileResult.total).append(" total, ").append(fileResult.documented)
                    .appendLine(" documented):")
                appendLine()
                for (elem in fileResult.undocumented) {
                    append("  ").append(elem.kind).append(' ')
                    if (elem.class_name.isNotEmpty()) append(elem.class_name).append('.')
                    append(elem.signature).append(" [line ").append(elem.line).appendLine("]")
                }
                appendLine()
            }
            append("Total: ").append(totalUndocumented).append(" undocumented across ").append(allFileResults.size)
                .append(" files (").append(psiFiles.size).append(" files checked)")
        }
    }

    private fun docLineOf(element: PsiElement, document: Document): Int {
        val offset = element.navigationElement.textOffset
        if (offset < 0 || offset >= document.textLength) return 0
        return document.getLineNumber(offset) + 1
    }

    private suspend fun findMissingJavaDocs(
        resolved: ResolvedFile,
        file_path: String,
        include_private: Boolean,
    ): FileMissingDocs {
        return readAction {
            val document = resolved.document
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val undocumented = mutableListOf<UndocumentedElement>()
            var total = 0
            var documented = 0

            fun processClass(cls: PsiClass, parentName: String) {
                if (!include_private && cls.hasModifierProperty(PsiModifier.PRIVATE)) return
                if (cls.name?.startsWith("$") == true) return

                total++
                if (cls.docComment != null) {
                    documented++
                } else {
                    val kind = when {
                        cls.isInterface -> "interface"
                        cls.isEnum -> "enum"
                        cls.isAnnotationType -> "annotation"
                        else -> "class"
                    }
                    undocumented.add(
                        UndocumentedElement(
                            kind = kind,
                            name = cls.name ?: "<anonymous>",
                            line = docLineOf(cls, document),
                            signature = cls.qualifiedName ?: cls.name ?: "",
                            class_name = parentName,
                        )
                    )
                }

                for (field in cls.fields) {
                    if (!include_private && field.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (field.name.startsWith("$") || field.name == "INSTANCE") continue

                    total++
                    if (field.docComment != null) {
                        documented++
                    } else {
                        val typeName = try {
                            field.type.presentableText
                        } catch (_: Exception) {
                            "?"
                        }
                        undocumented.add(
                            UndocumentedElement(
                                kind = "field",
                                name = field.name,
                                line = docLineOf(field, document),
                                signature = "$typeName ${field.name}",
                                class_name = cls.name ?: "",
                            )
                        )
                    }
                }

                for (method in cls.methods) {
                    if (!include_private && method.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (method.name.startsWith("$")) continue

                    total++
                    if (method.docComment != null) {
                        documented++
                    } else {
                        val params = method.parameterList.parameters.joinToString(", ") { p ->
                            val typeName = try {
                                p.type.presentableText
                            } catch (_: Exception) {
                                "?"
                            }
                            "$typeName ${p.name}"
                        }
                        val returnType = try {
                            method.returnType?.presentableText ?: "void"
                        } catch (_: Exception) {
                            "?"
                        }
                        undocumented.add(
                            UndocumentedElement(
                                kind = if (method.isConstructor) "constructor" else "method",
                                name = method.name,
                                line = docLineOf(method, document),
                                signature = "$returnType ${method.name}($params)",
                                class_name = cls.name ?: "",
                            )
                        )
                    }
                }

                for (inner in cls.innerClasses) {
                    processClass(inner, cls.name ?: "")
                }
            }

            for (cls in classes) {
                processClass(cls, "")
            }

            FileMissingDocs(
                file_path = file_path,
                undocumented = undocumented,
                total = total,
                documented = documented,
            )
        }
    }

    private suspend fun findMissingKotlinDocs(
        resolved: ResolvedFile,
        file_path: String,
        include_private: Boolean,
    ): FileMissingDocs {
        return readAction {
            val document = resolved.document
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            val undocumented = mutableListOf<UndocumentedElement>()
            var total = 0
            var documented = 0

            fun hasDoc(element: PsiElement): Boolean =
                PsiTreeUtil.getChildOfType(element, KDoc::class.java) != null

            fun isPrivate(decl: KtNamedDeclaration): Boolean =
                decl.hasModifier(KtTokens.PRIVATE_KEYWORD)

            fun processDeclarations(
                declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
                parentClassName: String
            ) {
                for (decl in declarations) {
                    when (decl) {
                        is KtEnumEntry -> continue
                        is KtClassOrObject -> {
                            if ((decl as? KtObjectDeclaration)?.isCompanion() == true) continue
                            if (!include_private && isPrivate(decl)) continue

                            total++
                            if (hasDoc(decl)) {
                                documented++
                            } else {
                                val kind = when {
                                    decl is KtObjectDeclaration -> "object"
                                    (decl as? KtClass)?.isInterface() == true -> "interface"
                                    (decl as? KtClass)?.isEnum() == true -> "enum"
                                    else -> "class"
                                }
                                undocumented.add(
                                    UndocumentedElement(
                                        kind = kind,
                                        name = decl.name ?: "<anonymous>",
                                        line = docLineOf(decl, document),
                                        signature = "$kind ${decl.name}",
                                        class_name = parentClassName,
                                    )
                                )
                            }

                            val body = decl.body ?: continue
                            processDeclarations(body.declarations, decl.name ?: "")
                        }

                        is KtNamedFunction -> {
                            if (!include_private && isPrivate(decl)) continue

                            total++
                            if (hasDoc(decl)) {
                                documented++
                            } else {
                                val params = decl.valueParameters.joinToString(", ") { p ->
                                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                                }
                                val ret = decl.typeReference?.text?.let { ": $it" } ?: ""
                                undocumented.add(
                                    UndocumentedElement(
                                        kind = "function",
                                        name = decl.name ?: "<anonymous>",
                                        line = docLineOf(decl, document),
                                        signature = "fun ${decl.name}($params)$ret",
                                        class_name = parentClassName,
                                    )
                                )
                            }
                        }

                        is KtProperty -> {
                            if (!include_private && isPrivate(decl)) continue

                            total++
                            if (hasDoc(decl)) {
                                documented++
                            } else {
                                val keyword = if (decl.isVar) "var" else "val"
                                val type = decl.typeReference?.text?.let { ": $it" } ?: ""
                                undocumented.add(
                                    UndocumentedElement(
                                        kind = "property",
                                        name = decl.name ?: "<anonymous>",
                                        line = docLineOf(decl, document),
                                        signature = "$keyword ${decl.name}$type",
                                        class_name = parentClassName,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            processDeclarations(ktFile.declarations, "")

            FileMissingDocs(
                file_path = file_path,
                undocumented = undocumented,
                total = total,
                documented = documented,
            )
        }
    }

    private suspend fun getDocByQualifiedName(
        project: com.intellij.openapi.project.Project,
        qualified_class_name: String,
        member_name: String,
        member_index: Int,
    ): List<DocEntry> {
        return readAction {
            val scope = GlobalSearchScope.allScope(project)
            val compiledClass = JavaPsiFacade.getInstance(project).findClass(qualified_class_name, scope)
                ?: mcpFail("Class '$qualified_class_name' not found")
            // Navigate to source if available (compiled classes don't carry doc comments)
            val psiClass = (compiledClass.navigationElement as? PsiClass) ?: compiledClass

            if (member_name.isEmpty()) {
                val doc = (psiClass as? PsiDocCommentOwner)?.docComment?.text
                listOf(
                    DocEntry(
                        psiClass.name ?: "<anonymous>",
                        "class ${compiledClass.qualifiedName}",
                        doc
                    )
                )
            } else {
                val methods = psiClass.findMethodsByName(member_name, false)
                val field = psiClass.findFieldByName(member_name, false)

                if (methods.isNotEmpty()) {
                    val targets = if (member_index >= 0) {
                        listOf(methods[member_index.coerceIn(methods.indices)])
                    } else {
                        methods.toList()
                    }
                    targets.map { m ->
                        // Navigate to source for doc comment
                        val srcMethod = (m.navigationElement as? PsiDocCommentOwner) ?: m
                        val params = m.parameterList.parameters.joinToString(", ") { p ->
                            "${p.type.presentableText} ${p.name}"
                        }
                        DocEntry(m.name, "$member_name($params)", srcMethod.docComment?.text)
                    }
                } else if (field != null) {
                    val srcField = (field.navigationElement as? PsiDocCommentOwner) ?: field
                    listOf(
                        DocEntry(
                            field.name ?: member_name,
                            field.text.lines().first().trim(),
                            srcField.docComment?.text
                        )
                    )
                } else {
                    mcpFail("Member '$member_name' not found in class '$qualified_class_name'")
                }
            }
        }
    }

    private suspend fun getJavaDoc(
        resolved: ResolvedFile,
        class_name: String,
        member_name: String,
        member_index: Int,
    ): List<DocEntry> {
        return readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val psiClass = if (class_name.isNotEmpty()) {
                findJavaClassByName(classes, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectJavaClassNames(classes)}")
            } else {
                classes[0]
            }

            if (member_name.isEmpty()) {
                val doc = psiClass.docComment?.text
                listOf(DocEntry(psiClass.name ?: "<anonymous>", "class ${psiClass.name}", doc))
            } else {
                val methods = psiClass.findMethodsByName(member_name, false)
                val field = psiClass.findFieldByName(member_name, false)

                if (methods.isNotEmpty()) {
                    val targets = if (member_index >= 0) {
                        listOf(methods[member_index.coerceIn(methods.indices)])
                    } else {
                        methods.toList()
                    }
                    targets.map { m ->
                        val params = m.parameterList.parameters.joinToString(", ") { p ->
                            "${p.type.presentableText} ${p.name}"
                        }
                        DocEntry(m.name, "$member_name($params)", m.docComment?.text)
                    }
                } else if (field != null) {
                    listOf(
                        DocEntry(
                            field.name ?: member_name,
                            field.text.lines().first().trim(),
                            field.docComment?.text
                        )
                    )
                } else {
                    mcpFail("Member '$member_name' not found in class '${psiClass.name}'")
                }
            }
        }
    }

    private suspend fun getKotlinDoc(
        resolved: ResolvedFile,
        class_name: String,
        member_name: String,
        member_index: Int,
    ): List<DocEntry> {
        return readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            if (class_name.isNotEmpty()) {
                val ktClass = findKotlinClassByName(ktFile.declarations, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")

                if (member_name.isEmpty()) {
                    val doc = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)?.text
                    listOf(DocEntry(ktClass.name ?: "<anonymous>", "class ${ktClass.name}", doc))
                } else {
                    val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
                    getKotlinMemberDocs(body.declarations, member_name, member_index, "in class '${ktClass.name}'")
                }
            } else if (member_name.isNotEmpty()) {
                val topLevel = ktFile.declarations.filter {
                    (it is KtNamedFunction && it.name == member_name) ||
                            (it is KtProperty && it.name == member_name)
                }
                if (topLevel.isNotEmpty()) {
                    getKotlinMemberDocs(ktFile.declarations, member_name, member_index, "at top level")
                } else {
                    val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                    if (classes.size == 1) {
                        val ktClass = classes[0]
                        val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
                        getKotlinMemberDocs(
                            body.declarations,
                            member_name,
                            member_index,
                            "as top-level or in class '${ktClass.name}'"
                        )
                    } else {
                        mcpFail("'$member_name' not found as a top-level declaration")
                    }
                }
            } else {
                val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                if (classes.isEmpty()) mcpFail("No classes found and no member_name specified")
                val ktClass = classes[0]
                val doc = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)?.text
                listOf(DocEntry(ktClass.name ?: "<anonymous>", "class ${ktClass.name}", doc))
            }
        }
    }

    private fun getKotlinMemberDocs(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        member_name: String,
        member_index: Int,
        context: String,
    ): List<DocEntry> {
        val functions = declarations.filter { it is KtNamedFunction && it.name == member_name }
        val properties = declarations.filter { it is KtProperty && it.name == member_name }

        if (functions.isNotEmpty()) {
            val targets = if (member_index >= 0) {
                listOf(functions[member_index.coerceIn(functions.indices)])
            } else {
                functions
            }
            return targets.map { f ->
                val func = f as KtNamedFunction
                val params = func.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                DocEntry(
                    func.name ?: member_name,
                    "$member_name($params)",
                    PsiTreeUtil.getChildOfType(f, KDoc::class.java)?.text
                )
            }
        }
        if (properties.isNotEmpty()) {
            val prop = properties[0] as KtProperty
            return listOf(
                DocEntry(
                    prop.name ?: member_name,
                    prop.text.lines().first().trim(),
                    PsiTreeUtil.getChildOfType(prop, KDoc::class.java)?.text
                )
            )
        }
        mcpFail("'$member_name' not found $context")
    }

    private fun findJavaClassByName(classes: Array<PsiClass>, name: String): PsiClass? {
        for (cls in classes) {
            if (cls.name == name) return cls
            val inner = findJavaClassByName(cls.innerClasses, name)
            if (inner != null) return inner
        }
        return null
    }

    private fun collectJavaClassNames(classes: Array<PsiClass>): List<String> {
        val names = mutableListOf<String>()
        for (cls in classes) {
            cls.name?.let { names.add(it) }
            names.addAll(collectJavaClassNames(cls.innerClasses))
        }
        return names
    }

    private fun findKotlinClassByName(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        name: String
    ): KtClassOrObject? {
        for (decl in declarations) {
            if (decl is KtClassOrObject) {
                if (decl.name == name) return decl
                val body = decl.body ?: continue
                val inner = findKotlinClassByName(body.declarations, name)
                if (inner != null) return inner
            }
        }
        return null
    }

    private fun collectKotlinClassNames(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>): List<String> {
        val names = mutableListOf<String>()
        for (decl in declarations) {
            if (decl is KtClassOrObject) {
                decl.name?.let { names.add(it) }
                val body = decl.body ?: continue
                names.addAll(collectKotlinClassNames(body.declarations))
            }
        }
        return names
    }

    private fun resolveKotlinMember(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        member_name: String,
        member_index: Int,
        context: String,
    ): PsiElement {
        val functions = declarations.filter { it is KtNamedFunction && it.name == member_name }
        val properties = declarations.filter { it is KtProperty && it.name == member_name }

        if (functions.size > 1 && member_index < 0) {
            val overloads = functions.mapIndexed { i, f ->
                val params = (f as KtNamedFunction).valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                "  $i: $member_name($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$member_name' $context. Specify member_index:\n$overloads")
        }

        if (functions.isNotEmpty()) {
            return functions[if (member_index >= 0) member_index.coerceIn(functions.indices) else 0]
        }
        if (properties.isNotEmpty()) {
            return properties[0]
        }
        mcpFail("'$member_name' not found $context")
    }

    private fun formatAsMarkdownDoc(documentation: String): String {
        return documentation.lines().joinToString("\n") { line ->
            if (line.isBlank()) "///" else "/// $line"
        }
    }

    private fun formatAsKDoc(documentation: String): String {
        val lines = documentation.lines().joinToString("\n") { line ->
            if (line.isBlank()) " *" else " * $line"
        }
        return "/**\n$lines\n */"
    }

    private suspend fun addJavaDoc(
        resolved: ResolvedFile,
        documentation: String,
        class_name: String,
        member_name: String,
        member_index: Int,
    ): AddDocResult {
        val project = resolved.psiFile.project

        val targetInfo = readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val psiClass = if (class_name.isNotEmpty()) {
                findJavaClassByName(classes, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectJavaClassNames(classes)}")
            } else {
                if (classes.size > 1 && member_name.isEmpty()) {
                    mcpFail("File has multiple classes: ${collectJavaClassNames(classes)}. Specify class_name.")
                }
                classes[0]
            }

            if (member_name.isEmpty()) {
                Triple(psiClass as PsiElement, psiClass.name ?: "<anonymous>", psiClass.docComment != null)
            } else {
                val methods = psiClass.findMethodsByName(member_name, false)
                val field = psiClass.findFieldByName(member_name, false)

                if (methods.size > 1 && member_index < 0) {
                    val overloads = methods.mapIndexed { i, m ->
                        val params = m.parameterList.parameters.joinToString(", ") { p ->
                            "${p.type.presentableText} ${p.name}"
                        }
                        "  $i: $member_name($params)"
                    }.joinToString("\n")
                    mcpFail("Multiple overloads found for '$member_name'. Specify member_index:\n$overloads")
                }

                if (methods.isNotEmpty()) {
                    val method = methods[if (member_index >= 0) member_index.coerceIn(methods.indices) else 0]
                    Triple(method as PsiElement, method.name, method.docComment != null)
                } else if (field != null) {
                    Triple(field as PsiElement, field.name ?: member_name, field.docComment != null)
                } else {
                    mcpFail("Member '$member_name' not found in class '${psiClass.name}'")
                }
            }
        }

        val (target, targetName, hadExistingDoc) = targetInfo

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = PsiElementFactory.getInstance(project)
                val docText = formatAsMarkdownDoc(documentation)
                val tempMethod = factory.createMethodFromText("$docText\nvoid _temp_() {}", target)
                val newDoc = (tempMethod as? PsiDocCommentOwner)?.docComment
                    ?: mcpFail("Failed to create markdown doc comment")

                val existingDoc = (target as? PsiDocCommentOwner)?.docComment
                if (existingDoc != null) {
                    existingDoc.replace(newDoc)
                } else {
                    target.addBefore(newDoc, target.firstChild)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return AddDocResult(
            success = true,
            targetName = targetName,
            replaced = hadExistingDoc,
            message = if (hadExistingDoc) "Replaced documentation on $targetName" else "Added documentation to $targetName"
        )
    }

    private suspend fun addKotlinDoc(
        resolved: ResolvedFile,
        documentation: String,
        class_name: String,
        member_name: String,
        member_index: Int,
    ): AddDocResult {
        val project = resolved.psiFile.project

        val targetInfo = readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            if (class_name.isNotEmpty()) {
                // Find specific class (including nested), optionally find member in it
                val ktClass = findKotlinClassByName(ktFile.declarations, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")

                if (member_name.isEmpty()) {
                    val existing = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)
                    Triple(ktClass as PsiElement, ktClass.name ?: "<anonymous>", existing != null)
                } else {
                    val body = ktClass.body
                        ?: mcpFail("Class '${ktClass.name}' has no body")
                    val member =
                        resolveKotlinMember(body.declarations, member_name, member_index, "in class '${ktClass.name}'")
                    val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                    Triple(member, (member as? KtNamedDeclaration)?.name ?: member_name, existing != null)
                }
            } else if (member_name.isNotEmpty()) {
                // Search top-level declarations first, then fall back to single-class member
                val topLevelDecls = ktFile.declarations.filter {
                    (it is KtNamedFunction && it.name == member_name) ||
                            (it is KtProperty && it.name == member_name)
                }

                if (topLevelDecls.isNotEmpty()) {
                    val member = resolveKotlinMember(ktFile.declarations, member_name, member_index, "at top level")
                    val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                    Triple(member, (member as? KtNamedDeclaration)?.name ?: member_name, existing != null)
                } else {
                    val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                    if (classes.size == 1) {
                        val ktClass = classes[0]
                        val body = ktClass.body
                            ?: mcpFail("Class '${ktClass.name}' has no body")
                        val member = resolveKotlinMember(
                            body.declarations,
                            member_name,
                            member_index,
                            "as top-level or in class '${ktClass.name}'"
                        )
                        val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                        Triple(member, (member as? KtNamedDeclaration)?.name ?: member_name, existing != null)
                    } else {
                        mcpFail("'$member_name' not found as a top-level declaration")
                    }
                }
            } else {
                // No class_name, no member_name → document the first/only class
                val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                if (classes.isEmpty()) mcpFail("No classes found and no member_name specified")
                if (classes.size > 1) {
                    mcpFail("File has multiple classes: ${classes.mapNotNull { it.name }}. Specify class_name.")
                }
                val ktClass = classes[0]
                val existing = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)
                Triple(ktClass as PsiElement, ktClass.name ?: "<anonymous>", existing != null)
            }
        }

        val (target, targetName, hadExistingDoc) = targetInfo

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = KtPsiFactory(project, markGenerated = true)
                val kdocText = formatAsKDoc(documentation)
                val tempFunc = factory.createFunction("$kdocText\nfun _temp_() { }")
                val newDoc = PsiTreeUtil.getChildOfType(tempFunc, KDoc::class.java)
                    ?: mcpFail("Failed to parse documentation as KDoc")

                val existingDoc = PsiTreeUtil.getChildOfType(target, KDoc::class.java)
                if (existingDoc != null) {
                    existingDoc.replace(newDoc)
                } else {
                    target.addBefore(newDoc, target.firstChild)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return AddDocResult(
            success = true,
            targetName = targetName,
            replaced = hadExistingDoc,
            message = if (hadExistingDoc) "Replaced documentation on $targetName" else "Added documentation to $targetName"
        )
    }
}
