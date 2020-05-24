package io.izzel.arclight.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject

class BuildSpigotTask extends DefaultTask {

    private Project project

    @Inject
    BuildSpigotTask(Project project) {
        this.project = project
    }

    private String mcVersion

    @Input
    String getMcVersion() {
        return mcVersion
    }

    void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion
        this.outSpigot = new File(outputDir, "spigot-${mcVersion}.jar")
    }

    private File buildTools

    @InputFile
    File getBuildTools() {
        return buildTools
    }

    void setBuildTools(File buildTools) {
        this.buildTools = buildTools
    }

    private File outputDir

    @OutputDirectory
    File getOutputDir() {
        return outputDir
    }

    void setOutputDir(File outputDir) {
        this.outputDir = outputDir
    }

    private File outSpigot

    @OutputFile
    File getOutSpigot() {
        return outSpigot
    }

    void setOutSpigot(File outSpigot) {
        this.outSpigot = outSpigot
    }

    @TaskAction
    void buildSpigot() {
        project.exec {
            workingDir = outputDir
            commandLine = [
                    'java', '-jar', buildTools.canonicalPath, '--rev', mcVersion
            ]
            standardOutput = System.out
        }
    }
}
