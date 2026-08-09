package com.naocraftlab

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyResourcePackTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getArchiveFile()

    @Input
    abstract Property<String> getTargetKey()

    @Input
    abstract MapProperty<String, String> getTargetProperties()

    @TaskAction
    void verifyArchive() {
        File archive = archiveFile.get().asFile
        Map<String, String> properties = new LinkedHashMap<>(targetProperties.get())
        ResourcePackVerifier.verify(archive, targetKey.get(), properties)
        logger.lifecycle("verified ${archive.name}: Minecraft ${properties.specMcVersions}; " +
                "${properties.specFogModel} fog; " +
                (TargetSpecification.configurable(properties) ? 'configurable' : 'static'))
    }
}
