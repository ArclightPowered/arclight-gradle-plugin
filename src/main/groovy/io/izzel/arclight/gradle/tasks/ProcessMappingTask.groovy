package io.izzel.arclight.gradle.tasks

import com.google.common.collect.MultimapBuilder
import com.google.common.collect.SetMultimap
import groovy.transform.CompileStatic
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
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode

import java.nio.file.Files
import java.nio.file.StandardOpenOption

@CompileStatic
class ProcessMappingTask extends DefaultTask {

    private static final def PKG = [
            'it', 'org/apache', 'jline', 'org/codehaus', 'org/eclipse'
    ]

    private File buildData
    private File inSrg
    private File inJar
    private String mcVersion
    private String bukkitVersion
    private File outDir
    private List<Closure<Void>> processors = new ArrayList<>()
    private String packageName

    @TaskAction
    void create() {
        def clFile = new File(buildData, "mappings/bukkit-$mcVersion-cl.csrg")
        def memberFile = new File(buildData, "mappings/bukkit-$mcVersion-members.csrg")
        def srg = new JarMapping()
        srg.loadMappings(inSrg)
        def csrg = new JarMapping()
        def remapper = new Remapper() {
            @Override
            String map(String className) {
                if (className == 'net/minecraft/server/MinecraftServer') return "net/minecraft/server/$bukkitVersion/MinecraftServer"
                if (className == 'net/minecraft/server/Main') return "net/minecraft/server/$bukkitVersion/Main"
                if (className.contains('/')) {
                    if (className.startsWith('net/minecraft/') || className.startsWith('com/mojang/math/')) {
                        className = className.substring(className.lastIndexOf('/') + 1)
                    } else {
                        return className
                    }
                } else {
                    if (className.charAt(0).isLowerCase()) return className
                }
                return "net/minecraft/server/$bukkitVersion/$className"
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
        csrg.classes.put('net/minecraft/server/Main', "net/minecraft/server/$bukkitVersion/Main".toString())
        PKG.each { String it -> csrg.packages.put(it, 'org/bukkit/craftbukkit/libs/' + it) }
        innerClasses(csrg, srg)
        csrg.loadMappings(Files.newBufferedReader(memberFile.toPath()), transformer, transformer, false)
        processors.forEach { Closure<Void> it -> it.call(csrg, srg) }
        def srgMethodAlias = MultimapBuilder.hashKeys().hashSetValues().build() as SetMultimap<String, String>
        srg.methods.entrySet().forEach { Map.Entry<String, String> it ->
            def spl = it.key.split(' ')
            def srgMethod = it.value
            if (srgMethod.startsWith('func_') || srgMethod.startsWith('m_')) {
                def i = spl[0].lastIndexOf('/')
                def notch = spl[0].substring(i + 1)
                srgMethodAlias.put(srgMethod, notch + ' ' + spl[1])
            } else {
                def i = spl[0].lastIndexOf('/')
                def notch = spl[0].substring(i + 1)
                srgMethodAlias.put(spl[0].substring(0, i + 1) + srgMethod, notch + ' ' + spl[1])
            }
        }
        def srgRev = srg.classes.collectEntries { Map.Entry<String, String> it -> [(it.value): it.key] }
        def csrgRev = csrg.classes.collectEntries { Map.Entry<String, String> it -> [(it.value): it.key] }
        def im = new InheritanceMap()
        def prov = new JointProvider()
        prov.add(new ClassLoaderProvider(ClassLoader.getSystemClassLoader()))
        prov.add(new JarProvider(Jar.init(inJar)))
        def mappingProv = new InheritanceProvider() {
            @Override
            Collection<String> getParents(String className) {
                def bukkit = csrg.classes.get(srgRev.get(className))
                if (!bukkit) return prov.getParents(className)
                def parents = prov.getParents(bukkit)
                if (!parents) return prov.getParents(className)
                return parents.collect { srg.classes.get(csrgRev.get(it)) ?: it }
            }
        }
        im.generate(mappingProv, srg.classes.values())
        Utils.using(new PrintWriter(Files.newBufferedWriter(outDir.toPath().resolve('inheritanceMap.txt'), StandardOpenOption.CREATE))) { PrintWriter it ->
            im.save(it)
        }

        def notchToCsrgMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return csrg.classes.get(internalName) ?: internalName
            }
        }
        def notchToSrgMapper = new Remapper() {
            @Override
            String map(String internalName) {
                return srg.classes.get(internalName) ?: internalName
            }
        }
        def packageMapper = new Remapper() {
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
        Utils.using(new PrintWriter(Files.newBufferedWriter(outDir.toPath().resolve('bukkit_srg.srg'), StandardOpenOption.CREATE))) { PrintWriter writer ->
            csrg.packages.each {
                writer.println("PK: ${it.value} ${it.key}")
            }
            srg.classes.each {
                if (it.value.startsWith('net/minecraft') && csrg.classes.containsKey(it.key)) {
                    writeClass(writer, csrg.classes.get(it.key), it.value)
                }
            }
            srg.fields.each {
                def i = it.key.lastIndexOf('/')
                def ownerCl = it.key.substring(0, i)
                if (csrg.classes.containsKey(ownerCl)) {
                    def notch = it.key.substring(i + 1)
                    def srgField = it.value
                    def csrgCl = csrg.classes.get(ownerCl)
                    def csrgField = csrg.fields.get("$csrgCl/$notch".toString()) ?: notch
                    writeField(writer, csrgCl, csrgField, "${srg.classes.get(ownerCl)}/$srgField")
                }
            }
            srg.methods.each { Map.Entry<String, String> it ->
                def spl = it.key.split(' ')
                def srgMethod = it.value
                def desc = spl[1]
                def i = spl[0].lastIndexOf('/')
                def ownerCl = spl[0].substring(0, i)
                def notch = spl[0].substring(i + 1)
                if (csrg.classes.containsKey(ownerCl)) {
                    def csrgCl = notchToCsrgMapper.map(ownerCl)
                    def csrgDesc = notchToCsrgMapper.mapMethodDesc(desc)
                    def csrgMethod = ProcessMappingTask.findCsrg(prov, csrgCl, notch, csrgDesc, csrg.methods, false)
                    if (csrgMethod == null) {
                        def extendSearch = !(srgMethod.startsWith('func_') || srgMethod.startsWith('m_'))
                        for (def alias : srgMethodAlias.get(extendSearch ? ownerCl + '/' + srgMethod : srgMethod)) {
                            if (alias != (notch + ' ' + desc)) {
                                def aliasName = alias.split(' ')[0]
                                def aliasDesc = alias.split(' ')[1]
                                def find = ProcessMappingTask.findCsrg(prov, csrgCl, aliasName, notchToCsrgMapper.mapMethodDesc(aliasDesc), csrg.methods, extendSearch)
                                if (find != null) {
                                    csrgMethod = find
                                    break
                                }
                            }
                        }
                    }
                    if (csrgMethod == null) csrgMethod = notch
                    this.writeMethod(writer, csrgCl, csrgMethod, packageMapper.mapMethodDesc(csrgDesc), "${srg.classes.get(ownerCl)}/$srgMethod ${notchToSrgMapper.mapMethodDesc(desc)}")
                }
            }
        }
    }

    @Lazy
    private Remapper officialPackageMapper = {
        def clFile = new File(buildData, "mappings/bukkit-$mcVersion-cl.csrg")
        def csrg = new JarMapping()
        csrg.loadMappings(clFile)
        csrg.classes.put('net/minecraft/server/MinecraftServer', 'net/minecraft/server/MinecraftServer')
        csrg.classes.put('net/minecraft/server/Main', 'net/minecraft/server/Main')
        def classes = csrg.classes.values().collectEntries { [(it.substring(it.lastIndexOf('/') + 1)): it.substring(0, it.lastIndexOf('/'))] }
        return new Remapper() {
            @Override
            String map(String internalName) {
                if (internalName.startsWith('net/minecraft')) {
                    def cl = internalName.substring(internalName.lastIndexOf('/') + 1)
                    if (classes.containsKey(cl)) {
                        return "${classes.get(cl)}/$cl"
                    } else if (internalName.contains('$')) {
                        def i = internalName.lastIndexOf('$')
                        return map(internalName.substring(0, i)) + internalName.substring(i)
                    } else {
                        println("No mapping found for " + internalName)
                    }
                }
                return internalName
            }
        }
    }()

    private void writeClass(PrintWriter writer, String csrg, String srg) {
        if (packageName == 'spigot') {
            writer.println("CL: $csrg $srg")
        } else if (packageName == 'official') {
            writer.println("CL: ${officialPackageMapper.map(csrg)} $srg")
        }
    }

    private void writeField(PrintWriter writer, String csrgCl, String csrgField, String srgContent) {
        if (packageName == 'spigot') {
            writer.println("FD: $csrgCl/$csrgField $srgContent")
        } else if (packageName == 'official') {
            writer.println("FD: ${officialPackageMapper.map(csrgCl)}/$csrgField $srgContent")
        }
    }

    private void writeMethod(PrintWriter writer, String csrgCl, String csrgMethod, String csrgDesc, String srgContent) {
        if (packageName == 'spigot') {
            writer.println("MD: $csrgCl/$csrgMethod $csrgDesc $srgContent")
        } else if (packageName == 'official') {
            writer.println("MD: ${officialPackageMapper.map(csrgCl)}/$csrgMethod ${officialPackageMapper.mapMethodDesc(csrgDesc)} $srgContent")
        }
    }

    private static String findCsrg(InheritanceProvider prov, String owner, String notch, String desc, Map<String, String> map, boolean extSearch) {
        def params = desc.substring(0, desc.lastIndexOf(')') + 1)
        for (def ret : allRet(prov, Type.getReturnType(desc).descriptor)) {
            for (def cl : allOf(prov, owner)) {
                def csrg = map.get("$cl/$notch $params$ret".toString())
                if (csrg) return csrg
                else if (extSearch && cl.contains('/')) {
                    def node = findNode(cl)
                    if (node) {
                        def methodNode = node.methods.find { it.name == notch && it.desc == params + ret }
                        if (methodNode) return notch
                    }
                }
            }
        }
        return null
    }

    private static ClassNode findNode(String name) {
        ClassReader cr
        try {
            cr = new ClassReader(name)
        } catch (Exception ignored) {
            return null
        }
        ClassNode node = new ClassNode()
        cr.accept(node, ClassReader.SKIP_CODE)
        return node
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

    private static void innerClasses(JarMapping csrg, JarMapping srg) {
        srg.classes.keySet().stream().filter { !csrg.classes.containsKey(it) }
                .filter { String c -> csrg.classes.keySet().findResult { it.startsWith(c) } != null }
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

    @Input
    String getPackageName() {
        return packageName
    }

    void setPackageName(String packageName) {
        this.packageName = packageName
    }
}
