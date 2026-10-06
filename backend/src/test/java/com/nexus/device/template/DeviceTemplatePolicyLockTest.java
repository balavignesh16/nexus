package com.nexus.device.template;

import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.device.domain.DeviceRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DeviceTemplatePolicyLockTest {
    @Test
    void referencedTemplatePolicyMutationChecksReferenceWhileHoldingTemplateLock() {
        var templates=mock(DeviceTemplateRepository.class);
        var capabilities=mock(DeviceTemplateCapabilityRepository.class);
        var definitions=mock(CapabilityDefinitionRepository.class);
        var devices=mock(DeviceRepository.class);
        UUID templateId=UUID.randomUUID();
        DeviceTemplate template=new DeviceTemplate(templateId,"env","Environment",null,null,null,
                ProtocolType.MQTT,true,Map.of());
        when(templates.findByIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(devices.existsByDeviceTemplateId(templateId)).thenReturn(true);
        DeviceTemplateService service=new DeviceTemplateService(templates,capabilities,definitions,devices,
                new BindingConstraintService());

        assertThrows(IllegalStateException.class,()->service.removeCapability(templateId,UUID.randomUUID()));

        var order=inOrder(templates,devices);
        order.verify(templates).findByIdForUpdate(templateId);
        order.verify(devices).existsByDeviceTemplateId(templateId);
        verifyNoInteractions(capabilities);
    }
}
