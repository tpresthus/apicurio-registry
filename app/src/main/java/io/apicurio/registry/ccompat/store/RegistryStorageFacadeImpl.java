/*
 * Copyright 2020 Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.registry.ccompat.store;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.Collections;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.apicurio.registry.ccompat.dto.CompatibilityCheckResponse;
import io.apicurio.registry.ccompat.dto.Schema;
import io.apicurio.registry.ccompat.dto.SchemaContent;
import io.apicurio.registry.ccompat.dto.SchemaInfo;
import io.apicurio.registry.ccompat.dto.SchemaReference;
import io.apicurio.registry.ccompat.dto.SubjectVersion;
import io.apicurio.registry.ccompat.rest.error.ConflictException;
import io.apicurio.registry.ccompat.rest.error.UnprocessableEntityException;
import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.rules.RuleApplicationType;
import io.apicurio.registry.rules.RuleViolationException;
import io.apicurio.registry.rules.RulesService;
import io.apicurio.registry.storage.ArtifactAlreadyExistsException;
import io.apicurio.registry.storage.ArtifactNotFoundException;
import io.apicurio.registry.storage.InvalidArtifactTypeException;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.RegistryStorageException;
import io.apicurio.registry.storage.RuleNotFoundException;
import io.apicurio.registry.storage.VersionNotFoundException;
import io.apicurio.registry.storage.dto.ArtifactMetaDataDto;
import io.apicurio.registry.storage.dto.ArtifactReferenceDto;
import io.apicurio.registry.storage.dto.ArtifactVersionMetaDataDto;
import io.apicurio.registry.storage.dto.ContentWrapperDto;
import io.apicurio.registry.storage.dto.OrderBy;
import io.apicurio.registry.storage.dto.OrderDirection;
import io.apicurio.registry.storage.dto.RuleConfigurationDto;
import io.apicurio.registry.storage.dto.SearchFilter;
import io.apicurio.registry.storage.dto.SearchedArtifactDto;
import io.apicurio.registry.storage.dto.StoredArtifactDto;
import io.apicurio.registry.types.ArtifactState;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.Current;
import io.apicurio.registry.types.RuleType;
import io.apicurio.registry.util.ArtifactTypeUtil;
import io.apicurio.registry.util.VersionUtil;

/**
 * @author Ales Justin
 * @author Jakub Senko 'jsenko@redhat.com'
 * @author Carles Arnal
 */
@ApplicationScoped
public class RegistryStorageFacadeImpl implements RegistryStorageFacade {

    private static final Pattern QUOTED_BRACKETS = Pattern.compile(": *\"\\{}\"");

    @Inject
    @Current
    RegistryStorage storage;

    @Inject
    RulesService rulesService;

    @Inject
    FacadeConverter converter;

    @Inject
    CCompatConfig cconfig;

    @Override
    public List<String> getSubjects() {
        return storage.searchArtifacts(Set.of(SearchFilter.ofGroup(null)), OrderBy.createdOn, OrderDirection.asc, 0, 1000)
                .getArtifacts()
                .stream()
                .filter(searchedArtifactDto -> isCcompatManagedType(searchedArtifactDto.getType()))
                .map(SearchedArtifactDto::getId).collect(Collectors.toList());
    }

    @Override
    public List<SubjectVersion> getSubjectVersions(int contentId) {
        if (cconfig.legacyIdModeEnabled.get()) {
            ArtifactMetaDataDto artifactMetaData = storage.getArtifactMetaData(contentId);
            return Collections.singletonList(converter.convert(artifactMetaData.getId(), artifactMetaData.getVersionId()));
        }

        return storage.getArtifactVersionsByContentId(contentId)
                .stream()
                .map(artifactMetaData -> converter.convert(artifactMetaData.getId(), artifactMetaData.getVersionId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Integer> deleteSubject(String subject) throws ArtifactNotFoundException, RegistryStorageException {
        return storage.deleteArtifact(null, subject)
                .stream()
                .map(VersionUtil::toInteger)
                .map(converter::convertUnsigned)
                .collect(Collectors.toList());
    }

    @Override
    public SchemaInfo getSchemaById(int contentId) throws ArtifactNotFoundException, RegistryStorageException {
        ContentHandle contentHandle;
        List<ArtifactReferenceDto> references;
        if (cconfig.legacyIdModeEnabled.get()) {
            StoredArtifactDto artifactVersion = storage.getArtifactVersion(contentId);
            contentHandle = artifactVersion.getContent();
            references = artifactVersion.getReferences();
        } else {
            ContentWrapperDto contentWrapper = storage.getArtifactByContentId(contentId);
            contentHandle = storage.getArtifactByContentId(contentId).getContent();
            references = contentWrapper.getReferences();
            List<ArtifactMetaDataDto> artifacts = storage.getArtifactVersionsByContentId(contentId);
            if (artifacts == null || artifacts.isEmpty()) {
                //the contentId points to an orphaned content
                throw new ArtifactNotFoundException("ContentId: " + contentId);
            }
        }
        return converter.convert(contentHandle, ArtifactTypeUtil.determineArtifactType(removeQuotedBrackets(contentHandle.content()), null, null, storage.resolveReferences(references)), references);
    }

    @Override
    public Schema getSchema(String subject, String versionString) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        return parseVersionString(subject, versionString,
                version -> {
                    if (ArtifactState.DISABLED.equals(storage.getArtifactVersionMetaData(null, subject, version).getState())) {
                        throw new VersionNotFoundException(null, subject, version);
                    }
                    StoredArtifactDto storedArtifact = storage.getArtifactVersion(null, subject, version);
                    return converter.convert(subject, storedArtifact, ArtifactTypeUtil.determineArtifactType(removeQuotedBrackets(storedArtifact.getContent().content()), null, null));
                });
    }

    @Override
    public List<Integer> getVersions(String subject) throws ArtifactNotFoundException, RegistryStorageException {
        return storage.getArtifactVersions(null, subject)
                .stream()
                .map(VersionUtil::toLong)
                .map(converter::convertUnsigned)
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public Schema getSchema(String subject, SchemaContent schema) throws ArtifactNotFoundException, RegistryStorageException {
        // Don't canonicalize the content when getting it - Confluent does not.
        ArtifactVersionMetaDataDto amd = storage.getArtifactVersionMetaData(null, subject, false, ContentHandle.create(schema.getSchema()));
        StoredArtifactDto storedArtifact = storage.getArtifactVersion(null, subject, amd.getVersion());
        return converter.convert(subject, storedArtifact);
    }

    @Override
    public Long createSchema(String subject, String schema, String schemaType, List<SchemaReference> references) throws ArtifactAlreadyExistsException, ArtifactNotFoundException, RegistryStorageException {
        // Check to see if this content is already registered - return the global ID of that content
        // if it exists.  If not, then register the new content.
        try {
            ContentHandle content = ContentHandle.create(schema);
            // Don't canonicalize the content when getting it - Confluent does not.
            ArtifactVersionMetaDataDto dto = storage.getArtifactVersionMetaData(null, subject, false, content);
            return cconfig.legacyIdModeEnabled.get() ? dto.getGlobalId() : dto.getContentId();
        } catch (ArtifactNotFoundException nfe) {
            // This is OK - when it happens just move on and create
        }

        // We validate the schema at creation time by inferring the type from the content
        try {
            Map<String, ContentHandle> resolvedReferences = resolveReferences(references);

            final ArtifactType artifactType = ArtifactTypeUtil.determineArtifactType(removeQuotedBrackets(schema), null, null, resolvedReferences);
            if (schemaType != null && !artifactType.value().equals(schemaType)) {
                throw new UnprocessableEntityException(String.format("Given schema is not from type: %s", schemaType));
            }
            ArtifactMetaDataDto artifactMeta = createOrUpdateArtifact(subject, schema, artifactType, references);
            return cconfig.legacyIdModeEnabled.get() ? artifactMeta.getGlobalId() : artifactMeta.getContentId();
        } catch (InvalidArtifactTypeException ex) {
            //If no artifact type can be inferred, throw invalid schema ex
            throw new UnprocessableEntityException(ex.getMessage());
        }
    }

    private Map<String, ContentHandle> resolveReferences(List<SchemaReference> references) {
        Map<String, ContentHandle> resolvedReferences = Collections.emptyMap();
        if (references != null && !references.isEmpty()) {
            //Transform the given references into dtos and set the contentId, this will also detect if any of the passed references does not exist.
            final List<ArtifactReferenceDto> referencesAsDtos = references.stream()
                    .map(schemaReference -> {
                        final ArtifactReferenceDto artifactReferenceDto = new ArtifactReferenceDto();
                        artifactReferenceDto.setArtifactId(schemaReference.getSubject());
                        artifactReferenceDto.setVersion(String.valueOf(schemaReference.getVersion()));
                        artifactReferenceDto.setName(schemaReference.getName());
                        artifactReferenceDto.setGroupId(null);
                        return artifactReferenceDto;
                    })
                    .collect(Collectors.toList());

            resolvedReferences = storage.resolveReferences(referencesAsDtos);
        }
        return resolvedReferences;
    }

    @Override
    public int deleteSchema(String subject, String versionString) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        return VersionUtil.toInteger(parseVersionString(subject, versionString, version -> {
            storage.deleteArtifactVersion(null, subject, version);
            return version;
        }));
    }

    @Override
    public void createOrUpdateArtifactRule(String subject, RuleType type, RuleConfigurationDto dto) {
        if (!doesArtifactRuleExist(subject, RuleType.COMPATIBILITY)) {
            storage.createArtifactRule(null, subject, RuleType.COMPATIBILITY, dto);
        } else {
            storage.updateArtifactRule(null, subject, RuleType.COMPATIBILITY, dto);
        }
    }

    @Override
    public void createOrUpdateGlobalRule(RuleType type, RuleConfigurationDto dto) {
        if (!doesGlobalRuleExist(RuleType.COMPATIBILITY)) {
            storage.createGlobalRule(RuleType.COMPATIBILITY, dto);
        } else {
            storage.updateGlobalRule(RuleType.COMPATIBILITY, dto);
        }
    }

    @Override
    public CompatibilityCheckResponse testCompatibilityBySubjectName(String subject, String version,
                                                                     SchemaContent request) {

        return parseVersionString(subject, version, v -> {
            try {
                final ArtifactVersionMetaDataDto artifact = storage
                        .getArtifactVersionMetaData(null, subject, v);
                rulesService.applyRules(null, subject, v, artifact.getType(),
                        ContentHandle.create(request.getSchema()), Collections.emptyMap());
                return CompatibilityCheckResponse.IS_COMPATIBLE;
            } catch (RuleViolationException ex) {
                return CompatibilityCheckResponse.IS_NOT_COMPATIBLE;
            }
        });
    }

    /**
     * Given a content removes any quoted brackets. This is useful for some validation corner cases in avro where some libraries detects quoted brackets as valid and others as invalid
     */
    private ContentHandle removeQuotedBrackets(String content) {
        return ContentHandle.create(QUOTED_BRACKETS.matcher(content).replaceAll(":{}"));
    }

    private ArtifactMetaDataDto createOrUpdateArtifact(String subject, String schema, ArtifactType artifactType, List<SchemaReference> references) {
        ArtifactMetaDataDto res;
        final List<ArtifactReferenceDto> parsedReferences = parseReferences(references);
        final Map<String, ContentHandle> resolvedReferences = storage.resolveReferences(parsedReferences);
        try {
            if (!doesArtifactExist(subject)) {
                rulesService.applyRules(null, subject, artifactType, ContentHandle.create(schema), RuleApplicationType.CREATE, resolvedReferences);
                res = storage.createArtifact(null, subject, null, artifactType, ContentHandle.create(schema), parsedReferences);
            } else {
                rulesService.applyRules(null, subject, artifactType, ContentHandle.create(schema), RuleApplicationType.UPDATE, resolvedReferences);
                res = storage.updateArtifact(null, subject, null, artifactType, ContentHandle.create(schema), parsedReferences);
            }
        } catch (RuleViolationException ex) {
            if (ex.getRuleType() == RuleType.VALIDITY) {
                throw new UnprocessableEntityException(ex.getMessage(), ex);
            } else {
                throw new ConflictException(ex.getMessage(), ex);
            }
        }
        return res;
    }

    /**
     * Given a version string:
     * - if it's an <b>integer</b>, use that;
     * - if it's a string "latest", find out and use the subject's (artifact's) latest version;
     * - otherwise throw an IllegalArgumentException.
     * On success, call the "then" function with the parsed version (MUST NOT be null) and return it's result.
     * Optionally provide an "else" function that will receive the exception that would be otherwise thrown.
     */
    @Override
    public <T> T parseVersionString(String subject, String versionString, Function<String, T> then) {
        String version;
        // TODO possibly need to ignore
        if ("latest".equals(versionString)) {
            ArtifactMetaDataDto latest = storage.getArtifactMetaData(null, subject);
            version = latest.getVersion();
        } else {
            try {
                Integer.parseUnsignedInt(versionString); // required by the spec, ignoring the possible leading "+"
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Illegal version format: " + versionString, e);
            }
            version = versionString;
        }
        return then.apply(version);
    }

    @Override
    public RuleConfigurationDto getGlobalRule(RuleType ruleType) {
        return storage.getGlobalRule(ruleType);
    }

    @Override
    public void deleteGlobalRule(RuleType ruleType) {
        storage.deleteGlobalRule(ruleType);
    }

    @Override
    public void deleteArtifactRule(String subject, RuleType ruleType) {
        storage.deleteArtifactRule(null, subject, ruleType);
    }

    @Override
    public RuleConfigurationDto getArtifactRule(String subject, RuleType ruleType) {
        return storage.getArtifactRule(null, subject, ruleType);
    }

    @Override
    public List<Long> getContentIdsReferencingArtifact(String subject, String versionString) {
        if (cconfig.legacyIdModeEnabled.get()) {
            return parseVersionString(subject, versionString,
                    version -> storage.getGlobalIdsReferencingArtifact(null, subject, version));
        }

        return parseVersionString(subject, versionString,
                version -> storage.getContentIdsReferencingArtifact(null, subject, version));
    }


    private boolean doesArtifactExist(String artifactId) {
        try {
            storage.getArtifact(null, artifactId);
            return true;
        } catch (ArtifactNotFoundException ignored) {
            return false;
        }
    }

    private boolean doesArtifactRuleExist(String artifactId, RuleType type) {
        try {
            storage.getArtifactRule(null, artifactId, type);
            return true;
        } catch (RuleNotFoundException | ArtifactNotFoundException ignored) {
            return false;
        }
    }

    private boolean doesGlobalRuleExist(RuleType type) {
        try {
            storage.getGlobalRule(type);
            return true;
        } catch (RuleNotFoundException ignored) {
            return false;
        }
    }

    //Parse references and resolve the contentId. This will fail with ArtifactNotFound if a reference cannot be found.
    private List<ArtifactReferenceDto> parseReferences(List<SchemaReference> references) {
        if (references != null) {
            return references.stream()
                    .map(schemaReference -> {
                        // Try to get the artifact version.  This will fail if not found with ArtifactNotFound or VersionNotFound
                        storage.getArtifactVersionMetaData(null, schemaReference.getSubject(), String.valueOf(schemaReference.getVersion()));
                        return new ArtifactReferenceDto(null, schemaReference.getSubject(), String.valueOf(schemaReference.getVersion()), schemaReference.getName());
                    }).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private boolean isCcompatManagedType(ArtifactType artifactType) {
        return artifactType.equals(ArtifactType.AVRO) || artifactType.equals(ArtifactType.PROTOBUF) || artifactType.equals(ArtifactType.JSON);
    }
}
