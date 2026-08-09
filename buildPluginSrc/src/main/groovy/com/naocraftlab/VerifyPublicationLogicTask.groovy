package com.naocraftlab

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets

abstract class VerifyPublicationLogicTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputDirectory
    abstract DirectoryProperty getReleasesDirectory()

    @InputFile
    abstract RegularFileProperty getGradlePropertiesFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

    @TaskAction
    void verifyLogic() {
        Map<String, String> common = PublicationSupport.loadProperties(
                gradlePropertiesFile.get().asFile)
        PublicationSupport.requireState(common.keySet() == ['rpName', 'rpVersion'] as Set<String>,
                'gradle.properties must only contain global resource-pack identity')
        String changelog = PublicationSupport.extractChangelog(
                changelogFile.get().asFile, common.rpVersion)
        List<Map<String, Object>> targets = PublicationSupport.buildTargets(
                repositoryDirectory.get().asFile, releasesDirectory.get().asFile,
                common, changelog)
        String version = common.rpVersion
        Map<String, Map<String, Object>> byKey = targets.collectEntries { [(it.key): it] }
        PublicationSupport.requireState(targets.every { target ->
            TargetSpecification.DEPRECATED_PROPERTIES.intersect(
                    target.properties.keySet()).empty
        }, 'target matrix contains redundant derived properties')
        PublicationSupport.requireState(
                !TargetSpecification.configurable(byKey['1.20.1'].properties) &&
                        !TargetSpecification.includesPresets(byKey['1.20.1'].properties) &&
                        TargetSpecification.packMetadata(byKey['1.20.1'].properties) == 'legacy',
                '1.20.1 must derive a static preset-free legacy target')
        PublicationSupport.requireState(
                TargetSpecification.configurable(byKey['1.21.10'].properties) &&
                        TargetSpecification.includesPresets(byKey['1.21.10'].properties) &&
                        TargetSpecification.packMetadata(byKey['1.21.10'].properties) == 'modern',
                '1.21.10 must derive configurable presets and modern pack metadata')
        Map<String, String> formatTwelve = new LinkedHashMap<>(byKey['1.21.10'].properties)
        formatTwelve.specRespackoptsVersion = '12'
        PublicationSupport.requireState(!TargetSpecification.includesPresets(formatTwelve),
                'Respackopts format 12 must not derive preset support')
        assertTarget(byKey['1.20.1'], "${version}+1.20.1", version, 'beta',
                ['3qAYkBMB'], ['958094'])
        assertTarget(byKey['1.21.10'], "${version}+1.21.10", version, 'beta',
                ['3qAYkBMB', 'TiF5QWZY'], ['958094', '430090'])
        assertTarget(byKey['26.2'], "${version}+26.2", version, 'release',
                ['3qAYkBMB', 'TiF5QWZY'], ['958094', '430090'])
        PublicationSupport.requireState(targets.every {
            it.uploadFilename == "${common.rpName}.zip"
        }, 'external upload filename changed')

        PublicationSupport.requireState(
                byKey['1.21.2'].gameVersions == ['1.21.2', '1.21.3'],
                '1.21.2 and 1.21.3 must share one publication target')
        Set<String> legacyKeys = ['1.21.1', '1.21.2', '1.21.4']
        List<Map<String, Object>> modrinth = targets.findAll { legacyKeys.contains(it.key) }
                .collect { VerifyPublicationLogicTask.exactModrinth(it) }
        List<Map<String, Object>> curseforge = targets.findAll { legacyKeys.contains(it.key) }
                .collect { VerifyPublicationLogicTask.exactCurseforge(it) }
        int modrinthSkips = 0
        int modrinthUploads = 0
        int curseforgeSkips = 0
        int curseforgeUploads = 0
        targets.each { target ->
            String modrinthAction = PublicationSupport.classify('modrinth', target, modrinth).action
            String curseforgeAction = PublicationSupport.classify('curseforge', target, curseforge).action
            if (modrinthAction == 'skip') {
                modrinthSkips++
            } else {
                modrinthUploads++
            }
            if (curseforgeAction == 'skip') {
                curseforgeSkips++
            } else {
                curseforgeUploads++
            }
        }
        PublicationSupport.requireState(
                [modrinthSkips, modrinthUploads, curseforgeSkips, curseforgeUploads] ==
                        [3, targets.size() - 3, 3, targets.size() - 3],
                'expected three existing legacy target families and every newer target missing')

        boolean duplicateArtifactsRejected = false
        try {
            PublicationSupport.requireUniqueArtifactContents([
                    [key: 'duplicate-a', artifactName: 'a.zip', artifactSha512: 'same'],
                    [key: 'duplicate-b', artifactName: 'b.zip', artifactSha512: 'same'],
            ])
        } catch (IllegalStateException exception) {
            duplicateArtifactsRejected = exception.message.contains('combined specMcVersions')
        }
        PublicationSupport.requireState(duplicateArtifactsRejected,
                'identical target artifacts must fail before publication')

        PublicationSupport.requireState(targets.every {
            PublicationSupport.classify('modrinth', it, []).action == 'upload' &&
                    PublicationSupport.classify('curseforge', it, []).action == 'upload'
        }, 'an empty remote state must upload every target')
        List<Map<String, Object>> allModrinth = targets.collect {
            VerifyPublicationLogicTask.exactModrinth(it)
        }
        List<Map<String, Object>> allCurseforge = targets.collect {
            VerifyPublicationLogicTask.exactCurseforge(it)
        }
        PublicationSupport.requireState(targets.every {
            PublicationSupport.classify('modrinth', it, allModrinth).action == 'skip' &&
                    PublicationSupport.classify('curseforge', it, allCurseforge).action == 'skip'
        }, 'an immediate rerun must skip every target')
        PublicationSupport.requireState(targets.every {
            PublicationSupport.classify('modrinth', it, allModrinth).action == 'skip' &&
                    PublicationSupport.classify('curseforge', it, []).action == 'upload'
        }, 'a rerun after Modrinth-only publication must skip Modrinth and upload CurseForge')
        Map<String, Object> partialTarget = byKey['1.21.11']
        PublicationSupport.requireState(
                PublicationSupport.classify('modrinth', partialTarget,
                        [VerifyPublicationLogicTask.exactModrinth(partialTarget)]).action == 'skip' &&
                        PublicationSupport.classify('curseforge', partialTarget, []).action == 'upload',
                'platform publication states must remain independent')

        Map<String, Object> releaseTarget = byKey['26.2']
        Map<String, Object> conflict = VerifyPublicationLogicTask.exactModrinth(releaseTarget)
        conflict.game_versions = ['26.1.2']
        PublicationSupport.requireState(
                PublicationSupport.classify('modrinth', releaseTarget, [conflict]).action == 'conflict',
                'same-name metadata conflict was not detected')
        Map<String, Object> versionNumberConflict =
                VerifyPublicationLogicTask.exactModrinth(releaseTarget)
        versionNumberConflict.version_number = "${version}+26.2"
        PublicationSupport.requireState(
                PublicationSupport.classify('modrinth', releaseTarget,
                        [versionNumberConflict]).action == 'conflict',
                'Modrinth version-number conflict was not detected')
        Map<String, Object> oldBeta = VerifyPublicationLogicTask.exactModrinth(releaseTarget)
        oldBeta.version_type = 'beta'
        PublicationSupport.requireState(
                PublicationSupport.classify('modrinth', releaseTarget, [oldBeta]).action == 'upload',
                'beta-to-release transition must create a new publication')
        Map<String, Object> oldCurseforgeBeta =
                VerifyPublicationLogicTask.exactCurseforge(releaseTarget)
        oldCurseforgeBeta.releaseType = 'beta'
        PublicationSupport.requireState(
                PublicationSupport.classify('curseforge', releaseTarget,
                        [oldCurseforgeBeta]).action == 'upload',
                'CurseForge beta-to-release transition must create a new publication')
        Map<String, Object> numericCurseforge = VerifyPublicationLogicTask.exactCurseforge(
                byKey['1.21.10'])
        numericCurseforge.releaseType = 2
        PublicationSupport.requireState(
                PublicationSupport.classify('curseforge', byKey['1.21.10'],
                        [numericCurseforge]).action == 'skip',
                'numeric CurseForge release types must be normalized')
        Map<String, Object> coreApiCurseforge = VerifyPublicationLogicTask.exactCurseforge(
                byKey['1.21.10'])
        coreApiCurseforge.releaseType = 2
        coreApiCurseforge.dependencies = coreApiCurseforge.relations.projects.collect {
            [modId: it.projectID, relationType: 3]
        }
        coreApiCurseforge.remove('relations')
        PublicationSupport.requireState(
                PublicationSupport.classify('curseforge', byKey['1.21.10'],
                        [coreApiCurseforge]).action == 'skip',
                'CurseForge Core API file metadata must be normalized')

        Map<String, Object> modrinthMetadata = PublicationSupport.modrinthMetadata(releaseTarget)
        PublicationSupport.requireState(
                modrinthMetadata.name == "${version}+26.2" &&
                        modrinthMetadata.version_number == version &&
                        modrinthMetadata.game_versions == ['26.2'] &&
                        modrinthMetadata.version_type == 'release' &&
                        modrinthMetadata.loaders == ['minecraft'],
                'Modrinth upload metadata changed')
        Map<String, Object> betaModrinthMetadata =
                PublicationSupport.modrinthMetadata(byKey['1.20.1'])
        PublicationSupport.requireState(
                betaModrinthMetadata.name == "${version}+1.20.1" &&
                        betaModrinthMetadata.version_number == version &&
                        betaModrinthMetadata.version_type == 'beta',
                'Modrinth beta metadata must keep the stable version coordinate')
        Map<String, Object> curseforgeMetadata = PublicationSupport.curseforgeMetadata(releaseTarget)
        PublicationSupport.requireState(
                curseforgeMetadata.displayName == "${version}+26.2" &&
                        curseforgeMetadata.gameVersionNames == ['26.2'] &&
                        curseforgeMetadata.releaseType == 'release' &&
                        curseforgeMetadata.changelogType == 'markdown',
                'CurseForge upload metadata changed')
        List<Map<String, Object>> curseforgeRelations = curseforgeMetadata.relations.projects
        PublicationSupport.requireState(
                curseforgeRelations*.projectID.every { it instanceof Integer } &&
                        curseforgeRelations*.projectID.toSet() == [958094, 430090] as Set<Integer>,
                'CurseForge relation project IDs must be JSON integers')
        Map<String, Object> betaCurseforgeMetadata =
                PublicationSupport.curseforgeMetadata(byKey['1.20.1'])
        PublicationSupport.requireState(
                betaCurseforgeMetadata.displayName == "${version}+1.20.1" &&
                        betaCurseforgeMetadata.releaseType == 'beta',
                'CurseForge beta metadata must keep the stable version coordinate')

        String boundary = 'PublicationLogicBoundary'
        byte[] multipart = PublicationSupport.multipart([
                [name: 'metadata', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(curseforgeMetadata)
                         .getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: releaseTarget.uploadFilename,
                 contentType: 'application/zip', bytes: 'zip'.getBytes(StandardCharsets.UTF_8)],
        ], boundary)
        String multipartText = new String(multipart, StandardCharsets.UTF_8)
        PublicationSupport.requireState(
                multipartText.count('name="metadata"') == 1,
                'multipart must contain one metadata part')
        PublicationSupport.requireState(
                multipartText.contains('filename="Foggy Pale Garden.zip"'),
                'multipart generic filename changed')
        PublicationSupport.requireState(
                multipartText.contains('"projectID":958094') &&
                        multipartText.contains('"projectID":430090') &&
                        !multipartText.contains('"projectID":"'),
                'multipart CurseForge relation project IDs must be serialized as numbers')
        logger.lifecycle("verified publication model: ${targets.size()} targets and " +
                "deterministic empty, partial, rerun, conflict, and channel-transition fixtures")
    }

    private static void assertTarget(Map<String, Object> target,
                                     String name,
                                     String versionNumber,
                                     String channel,
                                     List<String> modrinthDependencies,
                                     List<String> curseforgeDependencies) {
        PublicationSupport.requireState(target != null, "missing publication target ${name}")
        PublicationSupport.requireState(target.name == name, "incorrect platform name for ${target.key}")
        PublicationSupport.requireState(target.versionNumber == versionNumber,
                "incorrect Modrinth version number for ${target.key}")
        PublicationSupport.requireState(target.releaseType == channel,
                "incorrect release channel for ${target.key}")
        PublicationSupport.requireState(target.modrinthDependencies == modrinthDependencies,
                "incorrect Modrinth dependencies for ${target.key}")
        PublicationSupport.requireState(target.curseforgeDependencies == curseforgeDependencies,
                "incorrect CurseForge dependencies for ${target.key}")
    }

    private static Map<String, Object> exactModrinth(Map<String, Object> target) {
        [
                id            : "modrinth-${target.key}",
                name          : target.name,
                version_number: target.versionNumber,
                version_type  : target.releaseType,
                game_versions : target.gameVersions,
                loaders       : ['minecraft'],
                dependencies  : target.modrinthDependencies.collect {
                    [project_id: it, dependency_type: 'required']
                },
        ]
    }

    private static Map<String, Object> exactCurseforge(Map<String, Object> target) {
        [
                id          : "curseforge-${target.key}",
                displayName : target.name,
                releaseType : target.releaseType,
                gameVersions: target.gameVersions,
                relations   : [projects: target.curseforgeDependencies.collect {
                    [projectID: it, type: 'requiredDependency']
                }],
        ]
    }
}
