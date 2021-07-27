package io.izzel.arclight.gradle.tasks

import org.cadixdev.bombe.analysis.CachingInheritanceProvider
import org.cadixdev.bombe.analysis.ReflectionInheritanceProvider
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.srg.csrg.CSrgReader
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.objectweb.asm.commons.Remapper

import java.util.stream.Collectors

class ProcessMappingV2Task extends DefaultTask {

    private static final def PKG = [
            'it', 'org/apache', 'jline', 'org/codehaus', 'org/eclipse'
    ]
    private static final Map<String, String> KNOWN_WRONG_MAP = ['m_7870_': 'startDrownedConversion']

    private File buildData
    private File inSrg
    private File inJar
    private File inVanillaJar
    private String mcVersion
    private String bukkitVersion
    private File outDir
    private String packageName

    @TaskAction
    void create() {
        MappingSet csrg = MappingSet.create()
        def clFile = new File(buildData, "mappings/bukkit-$mcVersion-cl.csrg")
        clFile.withReader {
            new CSrgReader(it).read(csrg)
        }
        def memberFile = new File(buildData, "mappings/bukkit-$mcVersion-members.csrg")
        memberFile.withReader {
            csrg = csrg.merge(new CSrgReader(it).read())
        }
        def srg = MappingSet.create()
        inSrg.withReader {
            def data = it.lines().filter { String s -> !(s.startsWith('\t\t') || s.startsWith('tsrg2')) }.collect(Collectors.joining('\n'))
            new TSrgReader(new StringReader(data.toString())).read(srg)
        }
        def provider = new CachingInheritanceProvider(new ReflectionInheritanceProvider(new URLClassLoader(inVanillaJar.toURI().toURL())))
        innerClasses(csrg, srg)
        csrg.topLevelClassMappings.each {
            it.complete(provider)
        }
        srg.topLevelClassMappings.each {
            it.complete(provider)
        }
        if (!outDir.isDirectory()) {
            outDir.mkdirs()
        }
        new File(outDir, 'inheritanceMap.txt').with {
            it.delete()
            it.createNewFile()
        }
        def srgRev = srg.reverse()
        def finalMap = srgRev.merge(csrg).reverse()
        Map<String, String> seenNames = [:]
        finalMap.topLevelClassMappings.each {
            if (!it.fullObfuscatedName.contains('/')) {
                return
            }
            it.methodMappings.each { MethodMapping m ->
                if (!m.hasDeobfuscatedName()) {
                    return
                }
                def s = m.deobfuscatedName
                if (!s.startsWith('m_')) {
                    def c = srgRev.getTopLevelClassMapping(m.parent.deobfuscatedName).get()
                    if (c.getMethodMapping(m.deobfuscatedSignature).isEmpty()) {
                        if (m.obfuscatedName.length() > 4) {
                            def candidate = it.methodMappings.find { t -> t.obfuscatedName == m.obfuscatedName && t.deobfuscatedName != m.deobfuscatedName }
                            if (candidate) {
                                m.deobfuscatedName = candidate.deobfuscatedName
                            }
                        }
                        if (s == m.deobfuscatedName) {
                            println('No mapping found for ' + m)
                        }
                    }
                } else {
                    def old = seenNames[m.deobfuscatedName]
                    if (old) {
                        if ((old.length() > 2 || m.obfuscatedName.length() > 2) && old != m.obfuscatedName) {
                            if (old.length() > 2 && m.obfuscatedName.length() > 2) {
                                if (KNOWN_WRONG_MAP[m.deobfuscatedName] != m.obfuscatedName) {
                                    println("Duplicate [$old, ${m.obfuscatedName}] -> ${m.deobfuscatedName} in ${m.parent.fullDeobfuscatedName}")
                                }
                            } else {
                                seenNames[m.deobfuscatedName] = m.obfuscatedName.length() > old.length() ? m.obfuscatedName : old
                            }
                        }
                    } else {
                        seenNames[m.deobfuscatedName] = m.obfuscatedName
                    }
                }
            }
        }
        new File(outDir, 'bukkit_srg.srg').withWriter {
            PKG.each { pkg ->
                it.writeLine("PK: org/bukkit/craftbukkit/libs/$pkg $pkg")
            }
            new TSrgWriter(it) {
                def remapper = new Remapper() {
                    @Override
                    String map(String internalName) {
                        for (String pkg : PKG) {
                            if (internalName.startsWith(pkg)) {
                                return 'org/bukkit/craftbukkit/libs/' + internalName
                            }
                        }
                        return internalName
                    }
                }

                @Override
                protected void writeClassMapping(ClassMapping<?, ?> mapping) {
                    if (mapping.fullObfuscatedName.contains('/')) {
                        super.writeClassMapping(mapping)
                    }
                }

                @Override
                protected void writeMethodMapping(MethodMapping mapping) {
                    this.writer.println(String.format("\t%s %s %s",
                            seenNames.getOrDefault(mapping.getDeobfuscatedName(), mapping.getObfuscatedName()),
                            remapper.mapMethodDesc(mapping.getObfuscatedDescriptor()),
                            mapping.getDeobfuscatedName()))
                }
            }.write(finalMap)
        }
    }

    private static void innerClasses(MappingSet csrg, MappingSet srg) {
        srg.topLevelClassMappings.stream().filter { !csrg.hasTopLevelClassMapping(it.fullObfuscatedName) }
                .filter { TopLevelClassMapping mapping -> csrg.topLevelClassMappings.findResult { it.fullObfuscatedName.startsWith(mapping.fullObfuscatedName) } != null }
                .forEach {
                    name(it.fullObfuscatedName, '', csrg)
                }
    }

    private static void name(String cl, String suffix, MappingSet csrg) {
        def m = csrg.getTopLevelClassMapping(cl)
        if (m.isPresent()) {
            csrg.createTopLevelClassMapping(cl + suffix, m.get().fullObfuscatedName + suffix)
        } else {
            def i = cl.lastIndexOf('$')
            if (i <= 0) return
            def outer = cl.substring(0, i)
            name(outer, cl.substring(i) + suffix, csrg)
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

    @InputFile
    File getInJar() {
        return inJar
    }

    void setInJar(File inJar) {
        this.inJar = inJar
    }

    @InputFile
    File getInVanillaJar() {
        return inVanillaJar
    }

    void setInVanillaJar(File inVanillaJar) {
        this.inVanillaJar = inVanillaJar
    }

    @Input
    String getMcVersion() {
        return mcVersion
    }

    void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion
    }

    @Input
    String getBukkitVersion() {
        return bukkitVersion
    }

    void setBukkitVersion(String bukkitVersion) {
        this.bukkitVersion = bukkitVersion
    }

    @OutputDirectory
    File getOutDir() {
        return outDir
    }

    void setOutDir(File outDir) {
        this.outDir = outDir
    }

    @Input
    String getPackageName() {
        return packageName
    }

    void setPackageName(String packageName) {
        this.packageName = packageName
    }
}
