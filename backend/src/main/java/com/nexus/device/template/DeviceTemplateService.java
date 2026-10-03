package com.nexus.device.template;

import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.template.api.*;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional
public class DeviceTemplateService {
 private final DeviceTemplateRepository templates; private final DeviceTemplateCapabilityRepository capabilities;
 private final CapabilityDefinitionRepository definitions; private final DeviceRepository devices;
 private final BindingConstraintService constraints;
 public DeviceTemplateService(DeviceTemplateRepository t,DeviceTemplateCapabilityRepository c,CapabilityDefinitionRepository d,DeviceRepository devices,BindingConstraintService constraints){templates=t;capabilities=c;definitions=d;this.devices=devices;this.constraints=constraints;}
 @Transactional(readOnly=true) public List<DeviceTemplateResponse> all(){return templates.findAll().stream().map(t->DeviceTemplateResponse.from(t,devices.existsByDeviceTemplateId(t.getId()))).toList();}
 @Transactional(readOnly=true) public DeviceTemplateResponse get(UUID id){DeviceTemplate t=template(id);return DeviceTemplateResponse.from(t,devices.existsByDeviceTemplateId(t.getId()));}
 public DeviceTemplateResponse create(DeviceTemplateRequest r){String key=DeviceTemplate.normalizeKey(r.key()); if(templates.existsByKey(key))throw new DataIntegrityViolationException("Template key already exists"); return DeviceTemplateResponse.from(templates.save(new DeviceTemplate(UUID.randomUUID(),key,r.displayName(),r.description(),r.manufacturer(),r.model(),r.protocolType(),r.enabled()==null||r.enabled(),r.metadata())));}
 public DeviceTemplateResponse update(UUID id,UpdateDeviceTemplateRequest r){DeviceTemplate t=template(id);t.updateMetadata(r.displayName(),r.description(),r.manufacturer(),r.model(),r.enabled(),r.metadata());return DeviceTemplateResponse.from(t,devices.existsByDeviceTemplateId(t.getId()));}
 @Transactional(readOnly=true) public List<TemplateCapabilityResponse> capabilities(UUID id){template(id);return capabilities.findByTemplateIdOrderByDisplayOrder(id).stream().map(TemplateCapabilityResponse::from).toList();}
 public TemplateCapabilityResponse addCapability(UUID id,TemplateCapabilityRequest r){assertCompositionMutable(id);var definition=definitions.findById(r.capabilityDefinitionId()).orElseThrow(()->new ResourceNotFoundException("CapabilityDefinition",r.capabilityDefinitionId().toString()));if(capabilities.existsByTemplateIdAndDefinitionId(id,definition.getId()))throw new DataIntegrityViolationException("Capability already associated");constraints.unit(definition,r.defaultUnit());constraints.narrow(definition,r.configuration(),Map.of());return TemplateCapabilityResponse.from(capabilities.save(new DeviceTemplateCapability(UUID.randomUUID(),template(id),definition,r.required(),r.defaultUnit(),r.displayOrder(),r.freshnessThresholdSeconds(),r.configuration())));}
 public TemplateCapabilityResponse updateCapability(UUID templateId,UUID associationId,TemplateCapabilityRequest r){assertCompositionMutable(templateId);var c=capabilities.findById(associationId).orElseThrow(()->new ResourceNotFoundException("DeviceTemplateCapability",associationId.toString()));if(!c.getTemplate().getId().equals(templateId)||!c.getDefinition().getId().equals(r.capabilityDefinitionId()))throw new IllegalArgumentException("Template/capability identity is immutable");constraints.unit(c.getDefinition(),r.defaultUnit());constraints.narrow(c.getDefinition(),r.configuration(),Map.of());c.update(r.required(),r.defaultUnit(),r.displayOrder(),r.freshnessThresholdSeconds(),r.configuration());return TemplateCapabilityResponse.from(c);}
 public void removeCapability(UUID templateId,UUID associationId){assertCompositionMutable(templateId);var c=capabilities.findById(associationId).orElseThrow(()->new ResourceNotFoundException("DeviceTemplateCapability",associationId.toString()));if(!c.getTemplate().getId().equals(templateId))throw new IllegalArgumentException("Association does not belong to template");capabilities.delete(c);}
 private void assertCompositionMutable(UUID id){template(id);if(devices.existsByDeviceTemplateId(id))throw new IllegalStateException("Template capability composition is immutable once referenced by a device");}
 private DeviceTemplate template(UUID id){return templates.findById(id).orElseThrow(()->new ResourceNotFoundException("DeviceTemplate",id.toString()));}
}
