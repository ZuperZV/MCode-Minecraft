package com.github.zuperzv.mcodeminecraft.registry

import com.github.zuperzv.mcodeminecraft.assets.GradleMinecraftVersionDetector
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil

@Service(Service.Level.PROJECT)
class FluidScanner(private val project: Project) {

    fun findAllFluids(): Map<String, RegistryEntry> {
        val service = project.getService(RegistryIndexService::class.java)
        val jar = service.getServerJarPath()

        val loader = RegistryReportLoader()

        val projectFluids = findProjectFluids(project)
        val jarFluids = if (jar != null) loader.loadFallbackRegistries(jar)
            .filter { it.type == RegistryType.FLUID } else emptyList()

        return (projectFluids.values + jarFluids)
            .associateBy { it.id }
    }

    fun findProjectFluids(project: Project): Map<String, RegistryEntry> {
        val result = mutableMapOf<String, RegistryEntry>()

        val scope = GlobalSearchScope.projectScope(project)
        val files = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
        val psiManager = PsiManager.getInstance(project)

        for (vf in files) {
            val psiFile = psiManager.findFile(vf) as? PsiJavaFile ?: continue

            psiFile.accept(object : JavaRecursiveElementVisitor() {
                override fun visitField(field: PsiField) {
                    val type = field.type as? PsiClassType ?: return
                    val raw = type.rawType().canonicalText
                    if (raw != "java.util.function.Supplier") return

                    val parameters = type.parameters
                    if (parameters.isEmpty()) return

                    val param = parameters[0]
                    if (!param.canonicalText.contains("FlowingFluid")) return

                    val initializer = field.initializer?.text ?: return
                    val id = extractFluidId(initializer) ?: field.name.lowercase()

                    val detector = GradleMinecraftVersionDetector()
                    val namespace = detector.detect(project, "mod_id")

                    val entry = RegistryEntry(
                        id = namespace.toString()  + ":" + id,
                        namespace = namespace.toString(),
                        path = field.name.lowercase(),
                        type = RegistryType.FLUID
                    )

                    result[id] = entry
                }
            })
        }

        return result
    }

    private fun extractFluidId(initializer: String): String? {
        Regex("""register\s*\(\s*"([^"]+)"""").find(initializer)?.let {
            return it.groupValues[1]
        }

        Regex("""ResourceLocation\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"""").find(initializer)?.let {
            return "${it.groupValues[1]}:${it.groupValues[2]}"
        }

        return null
    }
}