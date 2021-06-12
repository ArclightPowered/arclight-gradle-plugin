package io.izzel.arclight.gradle

import org.gradle.api.Project

class ArclightExtension {

    private final Project project
    private String mcVersion
    private String bukkitVersion
    private String forgeVersion
    private File accessTransformer
    private boolean wipeVersion = false
    private boolean reobfVersion = false
    private List<String> installerInfo = new ArrayList<>()
    private boolean sharedSpigot = true
    private String packageName = "official"

    ArclightExtension(Project project) {
        this.project = project
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

    boolean getWipeVersion() {
        return wipeVersion
    }

    void setWipeVersion(boolean wipeVersion) {
        this.wipeVersion = wipeVersion
    }

    boolean getReobfVersion() {
        return reobfVersion
    }

    void setReobfVersion(boolean reobfVersion) {
        this.reobfVersion = reobfVersion
    }

    boolean getSharedSpigot() {
        return sharedSpigot
    }

    void setSharedSpigot(boolean sharedSpigot) {
        this.sharedSpigot = sharedSpigot
    }

    String getPackageName() {
        return packageName
    }

    void setPackageName(String packageName) {
        this.packageName = packageName
    }
}
