package com.naocraftlab

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final class PublicationSupport {
    static final String MODRINTH_PROJECT_ID = 'yKYDbWbQ'
    static final String CURSEFORGE_PROJECT_ID = '1314329'
    static final Map<String, String> MODRINTH_DEPENDENCIES = [
            polytone: '3qAYkBMB', respackopts: 'TiF5QWZY',
    ].asImmutable()
    static final Map<String, String> CURSEFORGE_DEPENDENCIES = [
            polytone: '958094', respackopts: '430090',
    ].asImmutable()

    private PublicationSupport() {
    }

    static Map<String, String> loadProperties(File file) {
        Properties loaded = new Properties()
        file.withInputStream { loaded.load(it) }
        Map<String, String> result = [:]
        loaded.stringPropertyNames().sort().each { key -> result[key] = loaded.getProperty(key).trim() }
        result
    }

    static String extractChangelog(File changelogFile, String version) {
        List<String> lines = changelogFile.readLines(StandardCharsets.UTF_8.name())
        String expected = "## ${version}"
        List<Integer> matches = []
        lines.eachWithIndex { line, index ->
            if (line.trim() == expected) {
                matches << index
            }
        }
        requireState(matches.size() == 1,
                "${changelogFile} must contain exactly one '${expected}' heading; " +
                        "found ${matches.size()}")
        int start = matches.first() + 1
        int end = lines.size()
        for (int index = start; index < lines.size(); index++) {
            if (lines[index] ==~ /^##\s+\S.*$/) {
                end = index
                break
            }
        }
        String body = lines.subList(start, end).join('\n').trim()
        requireState(body, "changelog section for ${version} is empty")
        body + '\n'
    }

    static List<Map<String, Object>> buildTargets(File repository,
                                                   File releasesDirectory,
                                                   Map<String, String> common,
                                                   String changelog) {
        String packName = common.rpName
        String packVersion = common.rpVersion
        TargetSpecification.validateCommon(common, 'gradle.properties')
        List<File> targetFiles = repository.toPath().resolve('mcVersions').toFile()
                .listFiles({ file -> file.name.endsWith('.properties') } as FileFilter)
                ?.sort { it.name } ?: []
        requireState(!targetFiles.empty, 'no Minecraft target properties found')
        Set<String> expectedArtifacts = []
        Set<String> platformNames = []
        List<Map<String, Object>> targets = []
        targetFiles.each { File targetFile ->
            Map<String, String> targetOverrides = loadProperties(targetFile)
            TargetSpecification.validateOverrides(targetOverrides, targetFile.toString())
            Map<String, String> properties = new LinkedHashMap<>(common)
            properties.putAll(targetOverrides)
            TargetSpecification.validate(properties, targetFile.toString())
            List<String> gameVersions = csv(properties.specMcVersions)
            String artifactVersion = TargetSpecification.artifactVersion(properties)
            String releaseType = properties.specReleaseType
            requireState(releaseType in ['release', 'beta'],
                    "${targetFile}: specReleaseType must be release or beta")
            boolean configurable = TargetSpecification.configurable(properties)
            String artifactName = "${packName} ${packVersion}+${artifactVersion}.zip"
            File artifact = new File(releasesDirectory, artifactName)
            requireState(artifact.isFile(), "expected verified artifact is missing: ${artifact}")
            expectedArtifacts << artifactName
            String versionNumber = packVersion
            String platformName = "${packVersion}+${artifactVersion}"
            requireState(platformNames.add(platformName), "duplicate publication name: ${platformName}")
            List<String> dependencyNames = configurable
                    ? ['polytone', 'respackopts'] : ['polytone']
            targets << [
                    key                    : targetFile.name - '.properties',
                    artifactVersion        : artifactVersion,
                    artifact               : artifact,
                    artifactName           : artifactName,
                    artifactSha512         : sha512(artifact),
                    uploadFilename         : "${packName}.zip",
                    name                   : platformName,
                    versionNumber          : versionNumber,
                    releaseType            : releaseType,
                    gameVersions           : gameVersions,
                    changelog              : changelog,
                    modrinthDependencies    : dependencyNames.collect { MODRINTH_DEPENDENCIES[it] },
                    curseforgeDependencies  : dependencyNames.collect { CURSEFORGE_DEPENDENCIES[it] },
                    properties             : properties,
            ]
        }
        Set<String> actualArtifacts = releasesDirectory.listFiles({ file ->
            file.name.endsWith('.zip')
        } as FileFilter)?.collect { it.name }?.toSet() ?: []
        requireState(actualArtifacts == expectedArtifacts,
                "release artifact set does not match targets: expected=${expectedArtifacts.sort()}, " +
                        "actual=${actualArtifacts.sort()}")
        requireUniqueArtifactContents(targets)
        targets
    }

    static void requireUniqueArtifactContents(List<Map<String, Object>> targets) {
        List<List<Map<String, Object>>> duplicates = targets.groupBy {
            it.artifactSha512
        }.values().findAll { it.size() > 1 }
        List<String> descriptions = duplicates.collect { group ->
            group.collect { "${it.key} (${it.artifactName})" }.join(', ')
        }
        requireState(duplicates.empty,
                'identical release artifacts must be represented by one target with combined ' +
                        'specMcVersions:\n- ' + descriptions.join('\n- '))
    }

    static Map<String, Object> classify(String platform,
                                        Map<String, Object> desired,
                                        List<Map<String, Object>> entries) {
        List<Map<String, Object>> sameCoordinate = entries.findAll {
            normalizedName(platform, it) == desired.name &&
                    normalizedChannel(platform, it) == desired.releaseType
        }
        if (sameCoordinate.size() > 1) {
            return [action: 'conflict',
                    reason: "multiple ${platform} entries use name '${desired.name}' " +
                            "in channel '${desired.releaseType}'",
                    remoteIds: sameCoordinate.collect { normalizedId(it) }]
        }
        if (sameCoordinate) {
            Map entry = sameCoordinate.first()
            List<String> mismatches = metadataMismatches(platform, desired, entry)
            if (mismatches) {
                return [action: 'conflict', reason: mismatches.join('; '),
                        remoteIds: [normalizedId(entry)]]
            }
            return [action: 'skip', reason: 'an exact publication already exists',
                    remoteIds: [normalizedId(entry)]]
        }
        Set<String> desiredGames = desired.gameVersions as Set<String>
        List<Map<String, Object>> overlapping = entries.findAll { entry ->
            normalizedChannel(platform, entry) == desired.releaseType &&
                    !(normalizedGames(platform, entry).intersect(desiredGames)).empty &&
                    normalizedName(platform, entry).startsWith(desired.versionNumber as String)
        }
        if (overlapping) {
            String descriptions = overlapping.collect {
                "'${normalizedName(platform, it)}' (${normalizedId(it)})"
            }.join(', ')
            return [action: 'conflict',
                    reason: "overlapping publication must be corrected first: ${descriptions}",
                    remoteIds: overlapping.collect { normalizedId(it) }]
        }
        [action: 'upload', reason: 'publication is missing', remoteIds: []]
    }

    static List<String> metadataMismatches(String platform,
                                           Map<String, Object> desired,
                                           Map<String, Object> entry) {
        List<String> mismatches = []
        Set<String> actualGames = normalizedGames(platform, entry)
        Set<String> desiredGames = desired.gameVersions as Set<String>
        if (actualGames != desiredGames) {
            mismatches << "game versions are ${actualGames.sort()}, expected ${desiredGames.sort()}"
        }
        String channel = normalizedChannel(platform, entry)
        if (channel != desired.releaseType) {
            mismatches << "channel is '${channel}', expected '${desired.releaseType}'"
        }
        Set<String> dependencies
        Set<String> expectedDependencies
        if (platform == 'modrinth') {
            if (entry.version_number?.toString() != desired.versionNumber) {
                mismatches << "version number is '${entry.version_number}', " +
                        "expected '${desired.versionNumber}'"
            }
            if (!(entry.loaders instanceof List) || !entry.loaders.contains('minecraft')) {
                mismatches << "loader list does not contain 'minecraft'"
            }
            dependencies = normalizedModrinthDependencies(entry)
            expectedDependencies = desired.modrinthDependencies as Set<String>
        } else {
            dependencies = normalizedCurseforgeDependencies(entry)
            expectedDependencies = desired.curseforgeDependencies as Set<String>
        }
        if (dependencies != null && dependencies != expectedDependencies) {
            mismatches << "required dependencies are ${dependencies.sort()}, " +
                    "expected ${expectedDependencies.sort()}"
        }
        mismatches
    }

    static String normalizedName(String platform, Map entry) {
        Object value = platform == 'modrinth' ? entry.name : entry.displayName
        value instanceof String ? value : ''
    }

    static String normalizedId(Map entry) {
        entry.id == null ? null : entry.id.toString()
    }

    static Set<String> normalizedGames(String platform, Map entry) {
        List<String> keys = platform == 'modrinth'
                ? ['game_versions'] : ['gameVersions', 'gameVersionNames']
        for (String key : keys) {
            if (entry[key] instanceof List) {
                return entry[key].collect { it.toString() }.toSet()
            }
        }
        [] as Set<String>
    }

    static String normalizedChannel(String platform, Map entry) {
        Object value = platform == 'modrinth' ? entry.version_type : entry.releaseType
        if (platform == 'curseforge' && value instanceof Number) {
            return [1: 'release', 2: 'beta', 3: 'alpha'][((Number) value).intValue()]
        }
        value instanceof String ? value.toLowerCase(Locale.ROOT) : null
    }

    static Set<String> normalizedModrinthDependencies(Map entry) {
        if (!(entry.dependencies instanceof List)) {
            return null
        }
        entry.dependencies.findAll {
            it instanceof Map && it.dependency_type == 'required' && it.project_id != null
        }.collect { it.project_id.toString() }.toSet()
    }

    static Set<String> normalizedCurseforgeDependencies(Map entry) {
        Object relations = entry.relations
        if (relations instanceof Map) {
            relations = relations.projects
        }
        if (!(relations instanceof List) && entry.dependencies instanceof List) {
            relations = entry.dependencies
        }
        if (!(relations instanceof List)) {
            return null
        }
        relations.findAll { relation ->
            if (!(relation instanceof Map)) {
                return false
            }
            Object type = relation.type != null ? relation.type : relation.relationType
            type in ['requiredDependency', 'required', 3]
        }.collect { relation ->
            Object id = relation.projectID != null ? relation.projectID :
                    (relation.projectId != null ? relation.projectId : relation.modId)
            id?.toString()
        }.findAll { it != null }.toSet()
    }

    static byte[] multipart(List<Map<String, Object>> parts, String boundary) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        parts.each { part ->
            output.write("--${boundary}\r\n".getBytes(StandardCharsets.US_ASCII))
            String disposition = "Content-Disposition: form-data; name=\"${quote(part.name)}\""
            if (part.filename != null) {
                disposition += "; filename=\"${quote(part.filename)}\""
            }
            output.write("${disposition}\r\n".getBytes(StandardCharsets.UTF_8))
            output.write("Content-Type: ${part.contentType}\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
            output.write(part.bytes as byte[])
            output.write('\r\n'.getBytes(StandardCharsets.US_ASCII))
        }
        output.write("--${boundary}--\r\n".getBytes(StandardCharsets.US_ASCII))
        output.toByteArray()
    }

    static Map<String, Object> modrinthMetadata(Map<String, Object> target) {
        [
                name          : target.name,
                version_number: target.versionNumber,
                changelog     : target.changelog,
                dependencies  : target.modrinthDependencies.collect {
                    [project_id: it, dependency_type: 'required']
                },
                game_versions : target.gameVersions,
                version_type  : target.releaseType,
                loaders       : ['minecraft'],
                featured      : false,
                status        : 'listed',
                project_id    : MODRINTH_PROJECT_ID,
                file_parts    : ['file'],
                primary_file  : 'file',
        ]
    }

    static Map<String, Object> curseforgeMetadata(Map<String, Object> target) {
        [
                changelog               : target.changelog,
                changelogType           : 'markdown',
                displayName             : target.name,
                gameVersionNames        : target.gameVersions,
                releaseType             : target.releaseType,
                isMarkedForManualRelease: false,
                relations               : [projects: CURSEFORGE_DEPENDENCIES
                        .findAll { slug, id -> target.curseforgeDependencies.contains(id) }
                        .collect { slug, id ->
                            [slug: slug, projectID: Integer.parseInt(id),
                             type: 'requiredDependency']
                        }],
        ]
    }

    static String sha512(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-512')
        file.withInputStream { input ->
            byte[] buffer = new byte[1024 * 1024]
            int count
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().encodeHex().toString()
    }

    static List<String> csv(String value) {
        List<String> result = value.split(',').collect { it.trim() }
        requireState(result && result.every { it },
                "expected a non-empty comma-separated list, got '${value}'")
        result
    }

    static void requireState(Object condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message)
        }
    }

    private static String quote(Object value) {
        value.toString().replace('\\', '\\\\').replace('"', '\\"')
    }
}
