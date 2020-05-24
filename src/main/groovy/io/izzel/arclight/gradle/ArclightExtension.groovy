package io.izzel.arclight.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies

class ArclightExtension {

    private final Project project
    private String mcVersion
    private String bukkitVersion
    private String forgeVersion
    private File accessTransformer
    private List<String> installerInfo = new ArrayList<>()

    ArclightExtension(Project project) {
        this.project = project
        setup()
    }

    void setup() {
        injectDependencies()
    }

    void injectDependencies() {
        def proj = project
        def file = project.file("${project.buildDir}/arclight_cache/spigot-$mcVersion-mapped-deobf.jar")
        def deps = project.configurations.compile.dependencies
        project.gradle.addListener(new DependencyResolutionListener() {
            @Override
            void beforeResolve(ResolvableDependencies resolvableDependencies) {
                if (file.exists()) {
                    println 1
                    deps.add(proj.dependencies.create(file))
                }
                proj.gradle.removeListener(this)
            }

            @Override
            void afterResolve(ResolvableDependencies resolvableDependencies) {
            }
        })
    }

    String getMcVersion() {
        return mcVersion
    }

    void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion
    }

    String getBukkitVersion() {
        return bukkitVersion
    }

    void setBukkitVersion(String bukkitVersion) {
        this.bukkitVersion = bukkitVersion
    }

    File getAccessTransformer() {
        return accessTransformer
    }

    void setAccessTransformer(File accessTransformer) {
        this.accessTransformer = accessTransformer
    }

    List<String> getInstallerInfo() {
        return installerInfo
    }

    void setInstallerInfo(List<String> installerInfo) {
        this.installerInfo = installerInfo
    }

    String getForgeVersion() {
        return forgeVersion
    }

    void setForgeVersion(String forgeVersion) {
        this.forgeVersion = forgeVersion
    }
}
