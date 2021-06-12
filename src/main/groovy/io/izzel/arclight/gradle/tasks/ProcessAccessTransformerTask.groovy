package io.izzel.arclight.gradle.tasks

import groovy.transform.CompileStatic
import io.izzel.arclight.gradle.Utils
import net.md_5.specialsource.JarMapping
import net.md_5.specialsource.JarRemapper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.Files

@CompileStatic
class ProcessAccessTransformerTask extends DefaultTask {

    private File buildData
    private File inSrg
    private String mcVersion
    private File outDir

    @TaskAction
    void create() {
        def clFile = new File(buildData, "mappings/bukkit-$mcVersion-cl.csrg")
        def memberFile = new File(buildData, "mappings/bukkit-$mcVersion-members.csrg")
        def atFile = new File(buildData, "mappings/bukkit-${mcVersion}.at")
        def ats = Files.lines(atFile.toPath(), StandardCharsets.UTF_8).filter {
            !it.startsWith('#') && !it.trim().empty
        }.collect { it.toString() }

        def srg = new JarMapping()
        srg.loadMappings(inSrg)

        def csrg = new JarMapping()
        Utils.using(new BufferedReader(Files.newBufferedReader(clFile.toPath()))) { BufferedReader it ->
            csrg.loadMappings(it, null, null, true)
        }
        csrg.loadMappings(memberFile)
        def csrgMapper = new JarRemapper(csrg)
        def srgMapper = new JarRemapper(srg)

        def f = csrg.fields.entrySet().collectEntries { Map.Entry<String, String> it ->
            def cl = it.key.substring(0, it.key.lastIndexOf('/'))
            def old = it.key.substring(it.key.lastIndexOf('/') + 1)
            def key = csrgMapper.map(cl) + '/' + it.value
            [(key): old]
        } as Map<String, String>
        csrg.fields.clear()
        csrg.fields.putAll(f)

        def m = csrg.methods.entrySet().collectEntries { Map.Entry<String, String> it ->
            def spl = it.key.split(' ')
            def cl = spl[0].substring(0, spl[0].lastIndexOf('/'))
            def old = spl[0].substring(spl[0].lastIndexOf('/') + 1)
            def desc = spl[1]
            def key = csrgMapper.map(cl) + '/' + it.value + ' ' + csrgMapper.mapMethodDesc(desc)
            [(key): old]
        } as Map<String, String>
        csrg.methods.clear()
        csrg.methods.putAll(m)

        outDir.toPath().resolve('bukkit_at.at').withWriter { Writer writer ->
            ats.each { String s ->
                def spl = s.split(' ', 2)
                def access = spl[0].replace('inal', '')
                def entry = spl[1]
                def i = entry.indexOf('(')
                if (i != -1) {
                    def desc = csrgMapper.mapMethodDesc(entry.substring(i))
                    def name = entry.substring(0, i)
                    def i2 = name.lastIndexOf('/')
                    def method = name.substring(i2 + 1)
                    def cl = csrgMapper.map(name.substring(0, i2))
                    def notch = csrgMapper.mapMethodName(cl, method, desc)
                    def srgDesc = srgMapper.mapMethodDesc(desc)
                    def srgMethod = srgMapper.mapMethodName(cl, notch, desc)
                    writer.println("$access ${srgMapper.map(cl).replace('/', '.')} $srgMethod${srgDesc}")
                } else {
                    if (entry.matches("^\\S+/[a-z]+/(\\w|\\\$)+\$")) {
                        writer.println("$access ${srgMapper.map(csrgMapper.map(entry)).replace('/', '.')}")
                    } else {
                        def i2 = entry.lastIndexOf('/')
                        def cl = csrgMapper.map(entry.substring(0, i2))
                        def field = entry.substring(i2 + 1)
                        def notch = csrg.fields.get(cl + '/' + field)
                        if (notch) {
                            def srgField = srgMapper.mapFieldName(cl, notch, null)
                            writer.println("$access ${srgMapper.map(cl).replace('/', '.')} $srgField")
                        } else {
                            println("No mapping found for $s")
                        }
                    }
                }
            }
        }
    }

    @InputDirectory
    File getBuildData() {
        return buildData
    }

    void setBuildData(File buildData) {
        this.buildData = buildData
    }

    @InputFile
    File getInSrg() {
        return inSrg
    }

    void setInSrg(File inSrg) {
        this.inSrg = inSrg
    }

    @Input
    String getMcVersion() {
        return mcVersion
    }

    void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion
    }

    @Input
    File getOutDir() {
        return outDir
    }

    void setOutDir(File outDir) {
        this.outDir = outDir
    }
}
