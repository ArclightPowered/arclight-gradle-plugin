## arclight-gradle-plugin

Gradle plugin designed for Arclight development.

![](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.izzel.io%2Freleases%2Fio%2Fizzel%2Farclight%2Farclight-gradle-plugin%2Fmaven-metadata.xml) ![](https://img.shields.io/github/workflow/status/ArclightTeam/arclight-gradle-plugin/Java%20CI%20with%20Gradle)

## Usage

```groovy
buildscript {
    repositories {
        maven { url = 'https://maven.izzel.io/releases' }
    }
    dependencies {
        classpath 'io.izzel.arclight:arclight-gradle-plugin:VERSION'
    }
}
```
