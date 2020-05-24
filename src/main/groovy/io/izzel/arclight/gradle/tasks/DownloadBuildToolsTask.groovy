package io.izzel.arclight.gradle.tasks

import io.izzel.arclight.gradle.Utils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class DownloadBuildToolsTask extends DefaultTask {

    static def URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"

    private File output

    @OutputFile
    File getOutput() {
        return output
    }

    void setOutput(File output) {
        this.output = output
    }

    @TaskAction
    void downloadBuildTools() {
        Utils.download(URL, output)
    }
}
