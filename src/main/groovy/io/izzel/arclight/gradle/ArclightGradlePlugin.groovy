package io.izzel.arclight.gradle

import groovy.json.JsonOutput
import io.izzel.arclight.gradle.tasks.BuildSpigotTask
import io.izzel.arclight.gradle.tasks.ProcessMappingTask
import io.izzel.arclight.gradle.tasks.DownloadBuildToolsTask
import io.izzel.arclight.gradle.tasks.RemapSpigotTask
import net.minecraftforge.gradle.common.task.ExtractMCPData
import net.minecraftforge.gradle.mcp.task.GenerateSRG
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyArtifact
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.StopExecutionException

class ArclightGradlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!project.extensions.findByName('minecraft')) {
            throw new InvalidUserDataException("Could not find property 'minecraft' on $project, ensure ForgeGradle is applied.")
        }
        def arclightExt = project.extensions.create('arclight', ArclightExtension, project)
        def conf = project.configurations.create('arclight')
        project.configurations.compile.extendsFrom(conf)
        def buildTools = project.file("${project.buildDir}/arclight_cache/buildtools")
        def buildToolsFile = new File(buildTools, 'BuildTools.jar')
        def downloadSpigot = project.tasks.create('downloadBuildTools', DownloadBuildToolsTask, {
            it.output = buildToolsFile
        })
        downloadSpigot.doFirst {
            if (buildToolsFile.exists()) throw new StopExecutionException()
        }
        def buildSpigot = project.tasks.create('buildSpigotTask', BuildSpigotTask, project)
        def processMapping = project.tasks.create('processMapping', ProcessMappingTask)
        def remapSpigot = project.tasks.create('remapSpigotJar', RemapSpigotTask)
        def generateMeta = project.tasks.create('generateArclightMeta', Copy)
        def metaFolder = project.file("${project.buildDir}/arclight_cache/meta")
        project.sourceSets.main.output.dir metaFolder, builtBy: generateMeta
        generateMeta.configure { Copy task ->
            task.into(metaFolder)
            task.outputs.upToDateWhen { false }
            task.dependsOn(remapSpigot)
        }
        project.afterEvaluate {
            buildSpigot.doFirst {
                if (new File(buildTools, "spigot-${arclightExt.mcVersion}.jar").exists()) {
                    throw new StopExecutionException()
                }
            }
            buildSpigot.configure { BuildSpigotTask task ->
                task.buildTools = buildToolsFile
                task.outputDir = buildTools
                task.mcVersion = arclightExt.mcVersion
                task.dependsOn(downloadSpigot)
            }
            def extractSrg = project.tasks.getByName('extractSrg') as ExtractMCPData
            def createSrgToMcp = project.tasks.getByName('createSrgToMcp') as GenerateSRG
            processMapping.configure { ProcessMappingTask task ->
                task.buildData = new File(buildTools, 'BuildData')
                task.mcVersion = arclightExt.mcVersion
                task.bukkitVersion = arclightExt.bukkitVersion
                task.outDir = project.file("${project.buildDir}/arclight_cache/tmp_srg")
                task.inSrg = extractSrg.output
                task.inJar = new File(buildTools, "spigot-${arclightExt.mcVersion}.jar")
                task.dependsOn(extractSrg, createSrgToMcp, buildSpigot)
            }
            remapSpigot.configure { RemapSpigotTask task ->
                task.ssJar = new File(buildTools, 'BuildData/bin/SpecialSource.jar')
                task.inJar = new File(buildTools, "spigot-${arclightExt.mcVersion}.jar")
                task.inSrg = new File(processMapping.outDir, 'bukkit_srg.srg')
                task.inSrgToStable = createSrgToMcp.output
                task.inheritanceMap = new File(processMapping.outDir, 'inheritanceMap.txt')
                task.outJar = project.file("${project.buildDir}/arclight_cache/spigot-${arclightExt.mcVersion}-mapped.jar")
                task.outDeobf = project.file("${project.buildDir}/arclight_cache/spigot-${arclightExt.mcVersion}-mapped-deobf.jar")
                task.dependsOn(processMapping)
                task.doFirst {
                    if (task.outJar.exists() && task.outDeobf.exists()) {
                        throw new StopExecutionException()
                    }
                }
            }
            generateMeta.configure { Copy task ->
                task.into(metaFolder) {
                    task.from(project.zipTree(project.file("${project.buildDir}/arclight_cache/spigot-${arclightExt.mcVersion}-mapped.jar")))
                    task.from(new File(project.file("${project.buildDir}/arclight_cache/tmp_srg"), 'inheritanceMap.txt'))
                    task.from(new File(project.file("${project.buildDir}/arclight_cache/tmp_srg"), 'bukkit_srg.srg'))
                }
                task.outputs.file(new File(metaFolder as File, 'META-INF/installer.json'))
                task.doLast {
                    def installer = new File(metaFolder, 'META-INF/installer.json')
                    def lib = new HashSet<String>()
                    def libs = project.configurations.arclight.dependencies.collect {
                        def classifier = null
                        if (it.artifacts) {
                            it.artifacts.each { DependencyArtifact artifact ->
                                if (artifact.classifier) {
                                    classifier = artifact.classifier
                                }
                            }
                        }
                        if (classifier) {
                            return "${it.group}:${it.name}:${it.version}:$classifier"
                        } else {
                            return "${it.group}:${it.name}:${it.version}"
                        }
                    }
                    lib.each { println(it) }
                    def output = [installer: [minecraft: arclightExt.mcVersion, forge: arclightExt.forgeVersion], libraries: libs]
                    installer.text = JsonOutput.toJson(output)
                }
            }
            if (remapSpigot.outDeobf.exists()) {
                project.configurations.compile.dependencies.add(project.dependencies.create(project.files(remapSpigot.outDeobf)))
            }
        }
    }
}