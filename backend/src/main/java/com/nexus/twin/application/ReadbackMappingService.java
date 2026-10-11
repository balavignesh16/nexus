package com.nexus.twin.application;

import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.template.DeviceTemplateCapabilityRepository;
import com.nexus.device.template.DeviceTemplateRepository;
import com.nexus.twin.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ReadbackMappingService {
    private final DeviceReadbackMappingRepository runtime;
    private final TemplateReadbackMappingRepository templates;
    private final DeviceCapabilityBindingRepository bindings;
    private final DeviceTemplateRepository templateRepository;
    private final DeviceTemplateCapabilityRepository templateCapabilities;
    private final DeviceRepository devices;
    private final ReadbackComparison comparison;
    private final VerificationService verification;
    public ReadbackMappingService(DeviceReadbackMappingRepository runtime,TemplateReadbackMappingRepository templates,
            DeviceCapabilityBindingRepository bindings,DeviceTemplateRepository templateRepository,
            DeviceTemplateCapabilityRepository templateCapabilities,DeviceRepository devices,
            ReadbackComparison comparison,VerificationService verification) {
        this.runtime=runtime;this.templates=templates;this.bindings=bindings;
        this.templateRepository=templateRepository;this.templateCapabilities=templateCapabilities;
        this.devices=devices;this.comparison=comparison;this.verification=verification;
    }
    public record Policy(UUID writeId,String subtarget,UUID readId,String selector,String selectorPath,
            String comparator,BigDecimal tolerance,String qualification,int timeoutSeconds) {}

    @Transactional
    public DeviceReadbackMapping addDevice(UUID deviceId,Policy policy) {
        devices.findByIdForUpdate(deviceId).orElseThrow();
        var pair=validateBindings(deviceId,policy);
        if(runtime.findByDeviceIdAndWriteBindingIdAndWriteSubtargetKeyAndEnabledTrue(deviceId,
                pair.get(0).getId(),policy.subtarget()).isPresent()) throw new IllegalArgumentException("Mapping already exists for write target");
        var mapping=new DeviceReadbackMapping(UUID.randomUUID(),deviceId,policy.writeId(),policy.subtarget(),
                policy.readId(),policy.selector(),policy.selectorPath(),policy.comparator(),policy.tolerance(),
                policy.qualification(),policy.timeoutSeconds(),null);
        runtime.saveAndFlush(mapping);
        verification.mappingAdded(deviceId,policy.writeId(),policy.subtarget());
        return mapping;
    }

    @Transactional
    public TemplateReadbackMapping addTemplate(UUID templateId,Policy policy) {
        templateRepository.findByIdForUpdate(templateId).orElseThrow();
        if(devices.existsByDeviceTemplateId(templateId)) throw new IllegalStateException("Referenced template mapping is immutable");
        var write=templateCapabilities.findById(policy.writeId()).orElseThrow();
        var read=templateCapabilities.findById(policy.readId()).orElseThrow();
        if(!write.getTemplate().getId().equals(templateId)||!read.getTemplate().getId().equals(templateId))
            throw new IllegalArgumentException("Template mapping must remain inside template");
        validatePolicy(policy,write.getDefinition().getDirection(),read.getDefinition().getDirection(),
                write.getDefinition().getValueType(),read.getDefinition().getValueType(),
                write.getDefaultUnit(),read.getDefaultUnit());
        if(!policy.subtarget().isEmpty()){
            if(write.getDefinition().getValueType()!=com.nexus.device.capability.definition.CapabilityValueType.OBJECT
                    ||!(write.getConfiguration().get("channels") instanceof List<?> channels)
                    ||channels.stream().noneMatch(n->n instanceof Number number
                        &&number.intValue()==Integer.parseInt(policy.subtarget().substring(8))))
                throw new IllegalArgumentException("Template write channel is not configured");
        }
        if(write.getDefinition().getValueType()==com.nexus.device.capability.definition.CapabilityValueType.ENUM
                &&!write.getDefinition().getEnumValues().equals(read.getDefinition().getEnumValues()))
            throw new IllegalArgumentException("Template enum domains differ");
        if(templates.findByTemplateId(templateId).stream().anyMatch(m->m.getWriteTemplateCapabilityId().equals(policy.writeId())
                && m.getWriteSubtargetKey().equals(policy.subtarget()))) throw new IllegalArgumentException("Template mapping already exists");
        return templates.saveAndFlush(new TemplateReadbackMapping(UUID.randomUUID(),templateId,policy.writeId(),
                policy.subtarget(),policy.readId(),policy.selector(),policy.selectorPath(),policy.comparator(),
                policy.tolerance(),policy.qualification(),policy.timeoutSeconds()));
    }

    @Transactional
    public void disableDevice(UUID deviceId,UUID mappingId) {
        devices.findByIdForUpdate(deviceId).orElseThrow();
        var mapping=runtime.findById(mappingId).orElseThrow();
        if(!mapping.getDeviceId().equals(deviceId))throw new IllegalArgumentException("Mapping belongs to another device");
        mapping.disable();runtime.saveAndFlush(mapping);
        verification.mappingUnavailable(deviceId,mapping.getWriteBindingId(),mapping.getWriteSubtargetKey());
    }

    public void materialize(UUID deviceId,UUID templateId,List<DeviceCapabilityBinding> created) {
        Map<UUID,DeviceCapabilityBinding> byTemplate=new HashMap<>();
        for(var binding:created)byTemplate.put(binding.getTemplateCapability().getId(),binding);
        for(var policy:templates.findByTemplateId(templateId)) {
            var write=byTemplate.get(policy.getWriteTemplateCapabilityId());
            var read=byTemplate.get(policy.getReadTemplateCapabilityId());
            if(write==null||read==null||!write.isEnabled()||!read.isEnabled())continue;
            var candidate=new Policy(write.getId(),policy.getWriteSubtargetKey(),read.getId(),policy.getSelectorKey(),
                    policy.getSelectorPath(),policy.getComparatorKey(),policy.getNumericTolerance(),
                    policy.getQualificationMode(),policy.getVerificationTimeoutSeconds());
            validatePolicy(candidate,write.getDefinition().getDirection(),read.getDefinition().getDirection(),
                    write.getDefinition().getValueType(),read.getDefinition().getValueType(),write.getUnit(),read.getUnit());
            runtime.save(new DeviceReadbackMapping(UUID.randomUUID(),deviceId,write.getId(),candidate.subtarget(),read.getId(),
                    candidate.selector(),candidate.selectorPath(),candidate.comparator(),candidate.tolerance(),
                    candidate.qualification(),candidate.timeoutSeconds(),policy.getId()));
        }
    }

    public Optional<DeviceReadbackMapping> usable(UUID deviceId,UUID writeId,String subtarget) {
        if(writeId==null)return Optional.empty();
        return runtime.findByDeviceIdAndWriteBindingIdAndWriteSubtargetKeyAndEnabledTrue(deviceId,writeId,subtarget)
                .filter(m->"COMMAND_ECHO".equals(m.getQualificationMode()))
                .filter(m->{try{validateBindings(deviceId,new Policy(writeId,subtarget,m.getReadBindingId(),
                    m.getSelectorKey(),m.getSelectorPath(),m.getComparatorKey(),m.getNumericTolerance(),
                    m.getQualificationMode(),m.getVerificationTimeoutSeconds()));return true;}
                    catch(RuntimeException invalid){return false;}});
    }

    private List<DeviceCapabilityBinding> validateBindings(UUID deviceId,Policy policy) {
        var write=bindings.findById(policy.writeId()).orElseThrow();
        var read=bindings.findById(policy.readId()).orElseThrow();
        if(!write.getDeviceId().equals(deviceId)||!read.getDeviceId().equals(deviceId)||!write.isEnabled()||!read.isEnabled())
            throw new IllegalArgumentException("Mapping bindings must be enabled on the same device");
        validatePolicy(policy,write.getDefinition().getDirection(),read.getDefinition().getDirection(),
                write.getDefinition().getValueType(),read.getDefinition().getValueType(),write.getUnit(),read.getUnit());
        if(!policy.subtarget().isEmpty()) {
            if(write.getDefinition().getValueType()!=com.nexus.device.capability.definition.CapabilityValueType.OBJECT
                    ||!(write.getConfiguration().get("channels") instanceof List<?>))
                throw new IllegalArgumentException("Channel subtarget requires configured object channels");
            List<?> channels=(List<?>)write.getConfiguration().get("channels");
            int channel=Integer.parseInt(policy.subtarget().substring(8));
            if(channels.stream().noneMatch(n->n instanceof Number number && number.intValue()==channel))
                throw new IllegalArgumentException("Write channel is not configured");
        }
        if(write.getDefinition().getValueType()==com.nexus.device.capability.definition.CapabilityValueType.ENUM
                && !write.getDefinition().getEnumValues().equals(read.getDefinition().getEnumValues()))
            throw new IllegalArgumentException("Enum domains differ");
        return List.of(write,read);
    }

    private void validatePolicy(Policy p,CapabilityDirection write,CapabilityDirection read,
            com.nexus.device.capability.definition.CapabilityValueType desiredType,
            com.nexus.device.capability.definition.CapabilityValueType readType,String writeUnit,String readUnit) {
        if(p.writeId()==null||p.readId()==null||p.writeId().equals(p.readId())||p.subtarget()==null
                ||!p.subtarget().matches("|channel:[1-9][0-9]*") || p.timeoutSeconds()<=0
                ||p.tolerance()!=null&&p.tolerance().signum()<0)
            throw new IllegalArgumentException("Invalid readback target or timeout");
        if(write!=CapabilityDirection.WRITE||read!=CapabilityDirection.READ)throw new IllegalArgumentException("WRITE to READ mapping required");
        if(p.qualification()==null||!Set.of("NONE","COMMAND_ECHO").contains(p.qualification()))
            throw new IllegalArgumentException("Unsupported qualification mode");
        if(!comparison.supports(p.selector(),p.selectorPath(),p.comparator(),
                desiredType==com.nexus.device.capability.definition.CapabilityValueType.OBJECT && !p.subtarget().isEmpty()
                    ? com.nexus.device.capability.definition.CapabilityValueType.BOOLEAN : desiredType,readType))
            throw new IllegalArgumentException("Incompatible selector/comparator");
        if(!Objects.equals(writeUnit,readUnit))throw new IllegalArgumentException("Readback units must match");
    }
}
