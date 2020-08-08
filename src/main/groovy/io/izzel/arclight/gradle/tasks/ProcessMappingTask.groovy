package io.izzel.arclight.gradle.tasks

import com.google.common.collect.MultimapBuilder
import com.google.common.collect.SetMultimap
import io.izzel.arclight.gradle.Utils
import net.md_5.specialsource.InheritanceMap
import net.md_5.specialsource.Jar
import net.md_5.specialsource.JarMapping
import net.md_5.specialsource.provider.ClassLoaderProvider
import net.md_5.specialsource.provider.InheritanceProvider
import net.md_5.specialsource.provider.JarProvider
import net.md_5.specialsource.provider.JointProvider
import net.md_5.specialsource.transformer.MappingTransformer
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Remapper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class ProcessMappingTask extends DefaultTask {

    private static final def PKG = [
            'it/unimi/dsi/fastutil', 'org/apache/commons', 'jline'
    ]

    private File buildData
    private File inSrg
    private File inJar
    private String mcVersion
    private String bukkitVersion
    private File outDir
    private List<Closure<Void>> processors = new ArrayList<>()

    @TaskAction
    void create() {
        def clFile = new File(buildData, "mappings/bukkit-$mcVersion-cl.csrg")
        def memberFile = new File(buildData, "mappings/bukkit-$mcVersion-members.csrg")
        def atFile = new File(buildData, "mappings/bukkit-${mcVersion}.at")
        def srg = new JarMapping()
        srg.loadMappings(inSrg)
        def csrg = new JarMapping()
        def remapper = new Remapper() {
            @Override
            String map(String className) {
                if (className == 'net/minecraft/server/MinecraftServer') return "net/minecraft/server/$bukkitVersion/MinecraftServer"
                if (className.charAt(0).isLowerCase()) return className
                if (className.contains('/')) return className
                "net/minecraft/server/$bukkitVersion/$className"
            }
        }
        def transformer = new MappingTransformer() {
            @Override
            String transformClassName(String className) {
                return remapper.map(className)
            }

            @Override
            String transformMethodDescriptor(String oldDescriptor) {
                return remapper.mapMethodDesc(oldDescriptor)
            }
        }
        csrg.loadMappings(Files.newBufferedReader(clFile.toPath()), transformer, transformer, false)
        csrg.classes.put('net/minecraft/server/MinecraftServer', "net/minecraft/server/$bukkitVersion/MinecraftServer".toString())
        PKG.each { csrg.packages.put(it, 'org/bukkit/craftbukkit/libs/' + it) }
        innerClasses(csrg, srg)
        csrg.loadMappings(Files.newBufferedReader(memberFile.toPath()), transformer, transformer, false)
        def ats = Files.lines(atFile.toPath(), StandardCharsets.UTF_8).filter {
            !it.startsWith('#') && !it.trim().empty
        }.collect { it.toString() }
        processors.forEach { it.call(csrg, ats, srg) }
        def srgMethodAlias = MultimapBuilder.hashKeys().hashSetValues().build() as SetMultimap<String, String>
        srg.methods.entrySet().forEach {
            def spl = it.key.split(' ')
            def srgMethod = it.value
            if (srgMethod.startsWith('func_')) {
                def i = spl[0].lastIndexOf('/')
                def notch = spl[0].substring(i + 1)
                srgMethodAlias.put(srgMethod, notch)
            }
        }
        def srgRev = srg.classes.collectEntries { [(it.value): it.key] }
        def csrgRev = csrg.classes.collectEntries { [(it.value): it.key] }
        def im = new InheritanceMap()
        def prov = new JointProvider()
        prov.add(new JarProvider(Jar.init(inJar)))
        prov.add(new ClassLoaderProvider(ClassLoader.getSystemClassLoader()))
        def mappingProv = new InheritanceProvider() {
            @Override
            Collection<String> getParents(String className) {
                def bukkit = csrg.classes.get(srgRev.get(className))
                if (!bukkit) return prov.getParents(className)
                return prov.getParents(bukkit).collect { srg.classes.get(csrgRev.get(it)) }
            }
        }
        im.generate(mappingProv, srg.classes.values())
        Utils.using(new PrintWriter(Files.newBufferedWriter(outDir.toPath().resolve('inheritanceMap.txt'), StandardOpenOption.CREATE))) {
            im.save(it)
        }
        im.generate(prov, csrg.classes.values())

        def versionFix = new Remapper() {
            @Override
            String map(String internalName) {
                if (internalName.startsWith('net/minecraft/server/') && !internalName.startsWith("net/minecraft/server/$bukkitVersion/")) {
                    internalName = internalName.replace('net/minecraft/server/', "net/minecraft/server/$bukkitVersion/")
                }
                return internalName
            }
        }
        def csrgToSrgMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return srg.classes.get(csrgRev.get(versionFix.map(internalName)))
            }
        }
        def csrgToNotchMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return csrgRev.get(internalName)
            }
        }
        def notchToCsrgMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return csrg.classes.get(internalName)
            }
        }
        def notchToSrgMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return srg.classes.get(internalName)
            }
        }
        Utils.using(new PrintWriter(Files.newBufferedWriter(outDir.toPath().resolve('bukkit_at.at'), StandardOpenOption.CREATE))) { writer ->
            ats.forEach {
                def spl = it.split(' ')
                def access = spl[0].replace('inal', '')
                def entry = spl[1]
                def i = entry.indexOf('(')
                if (i != -1) {
                    def desc = versionFix.mapMethodDesc(entry.substring(i))
                    def name = entry.substring(0, i)
                    def i2 = name.lastIndexOf('/')
                    def method = name.substring(i2 + 1)
                    def cl = versionFix.map(name.substring(0, i2))
                    def notch = ProcessMappingTask.find(im, cl, method, desc, csrg.methods)
                    def srgDesc = csrgToSrgMapper.mapMethodDesc(desc)
                    def srgMethod = srg.methods.get("${csrgToNotchMapper.map(cl)}/$notch ${csrgToNotchMapper.mapMethodDesc(desc)}".toString())
                    srgMethod = srgMethod == null ? notch : srgMethod
                    writer.println("$access ${csrgToSrgMapper.map(cl).replace('/', '.')} $srgMethod${srgDesc}")
                } else {
                    if (entry.count('/') == 3) {
                        writer.println("$access ${csrgToSrgMapper.map(entry).replace('/', '.')}")
                    } else if (entry.count('/') == 4) {
                        def i2 = entry.lastIndexOf('/')
                        def cl = versionFix.map(entry.substring(0, i2))
                        def field = entry.substring(i2 + 1)
                        def result = csrg.fields.findAll { it.value == field && it.key.startsWith(cl + '/') }
                        if (result.isEmpty()) {
                            println("No mapping found for $it")
                        } else {
                            def notch = result.iterator().next().key.replace(cl + '/', '')
                            def srgField = srg.fields.get("${csrgToNotchMapper.map(cl)}/${notch}".toString())
                            srgField = srgField == null ? notch : srgField
                            writer.println("$access ${csrgToSrgMapper.map(cl).replace('/', '.')} $srgField")
                        }
                    }
                }
            }
        }
        Utils.using(new PrintWriter(Files.newBufferedWriter(outDir.toPath().resolve('bukkit_srg.srg'), StandardOpenOption.CREATE))) { writer ->
            csrg.packages.each {
                writer.println("PK: ${it.value} ${it.key}")
            }
            srg.classes.each {
                if (it.value.startsWith('net/minecraft') && csrg.classes.containsKey(it.key)) {
                    writer.println("CL: ${csrg.classes.get(it.key)} ${it.value}")
                }
            }
            srg.fields.each {
                def i = it.key.lastIndexOf('/')
                def owner = it.key.substring(0, i)
                if (csrg.classes.containsKey(owner)) {
                    def notch = it.key.substring(i + 1)
                    def srgField = it.value
                    def csrgCl = csrg.classes.get(owner)
                    def csrgField = csrg.fields.get("$csrgCl/$notch".toString())
                    if (csrgField == null) csrgField = notch
                    writer.println("FD: $csrgCl/$csrgField ${srg.classes.get(owner)}/$srgField")
                }
            }
            srg.methods.each {
                def spl = it.key.split(' ')
                def srgMethod = it.value
                def desc = spl[1]
                def i = spl[0].lastIndexOf('/')
                def owner = spl[0].substring(0, i)
                def notch = spl[0].substring(i + 1)
                if (csrg.classes.containsKey(owner)) {
                    def csrgCl = notchToCsrgMapper.map(owner)
                    def csrgDesc = notchToCsrgMapper.mapMethodDesc(desc)
                    def csrgMethod = ProcessMappingTask.findCsrg(prov, csrgCl, notch, csrgDesc, csrg.methods)
                    if (csrgMethod == null) {
                        for (def alias : srgMethodAlias.get(srgMethod)) {
                            if (alias != notch) {
                                def find = ProcessMappingTask.findCsrg(prov, csrgCl, alias, csrgDesc, csrg.methods)
                                if (find != null) {
                                    csrgMethod = find
                                    break
                                }
                            }
                        }
                    }
                    if (csrgMethod == null) csrgMethod = notch
                    writer.println("MD: $csrgCl/$csrgMethod $csrgDesc ${srg.classes.get(owner)}/$srgMethod ${notchToSrgMapper.mapMethodDesc(desc)}")
                }
            }
        }
    }

    private static String findCsrg(InheritanceProvider prov, String owner, String notch, String desc, Map<String, String> map) {
        def params = desc.substring(0, desc.lastIndexOf(')') + 1)
        for (def ret : allRet(prov, Type.getReturnType(desc).descriptor)) {
            for (def cl : allOf(prov, owner)) {
                def csrg = map.get("$cl/$notch $params$ret".toString())
                if (csrg) return csrg
            }
        }
        return null
    }

    private static List<String> allRet(InheritanceProvider prov, String desc) {
        def type = Type.getType(desc)
        if (type.sort == Type.ARRAY) {
            return allRet(prov, type.elementType.descriptor).collect { ('[' * type.dimensions) + it }
        } else if (type.sort == Type.OBJECT) {
            return allOf(prov, type.internalName).collect { 'L' + it + ';' }
        } else return [type.descriptor]
    }

    private static List<String> allOf(InheritanceProvider prov, String owner) {
        def ret = new ArrayList<String>()
        def queue = new ArrayDeque<String>()
        queue.addFirst(owner)
        while (!queue.isEmpty()) {
            def cl = queue.pollFirst()
            def parents = prov.getParents(cl)
            if (parents) parents.each { queue.addLast(it) }
            ret.add(cl)
        }
        return ret
    }

    private static String find(InheritanceMap im, String root, String method, String desc, Map<String, String> map) {
        def queue = new ArrayDeque<String>()
        queue.addFirst(root)
        while (!queue.isEmpty()) {
            def cl = queue.pollFirst()
            im.getParents(cl).each { queue.addLast(it) }
            def result = map.findAll { it.value == method }.findAll { it.key.startsWith(cl + '/') && it.key.endsWith(desc) }
            if (!result.isEmpty()) {
                return result.iterator().next().key.replace(cl + '/', '').replace(desc, '').trim()
            }
        }
        if (method != '<init>') println("No mapping found for $root/$method $desc")
        return method
    }

    private static void innerClasses(JarMapping csrg, JarMapping srg) {
        srg.classes.keySet().stream().filter { !csrg.classes.containsKey(it) }
                .filter { c -> csrg.classes.keySet().findResult { it.startsWith(c) } != null }
                .forEach {
                    name(it, '', csrg)
                }
    }

    private static void name(String cl, String suffix, JarMapping csrg) {
        def s = csrg.classes.get(cl)
        if (s) {
            csrg.classes.put(cl + suffix, s + suffix)
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

    List<Closure<Void>> getProcessors() {
        return processors
    }

    void process(Closure<Void> closure) {
        processors.add(closure)
    }
}
