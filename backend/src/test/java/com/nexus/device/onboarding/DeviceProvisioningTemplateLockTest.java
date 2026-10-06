package com.nexus.device.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.device.binding.BindingConstraintService;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.onboarding.api.ProvisionDeviceRequest;
import com.nexus.device.template.*;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.application.FreshnessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DeviceProvisioningTemplateLockTest {
    @Test
    void provisioningLocksTemplateBeforeReadingItsCapabilityComposition() {
        var sessions=mock(DeviceOnboardingSessionRepository.class);
        var templates=mock(DeviceTemplateRepository.class);
        var members=mock(DeviceTemplateCapabilityRepository.class);
        var bindings=mock(DeviceCapabilityBindingRepository.class);
        var devices=mock(DeviceRepository.class);
        var spaces=mock(SpaceRepository.class);
        UUID templateId=UUID.randomUUID();
        DeviceTemplate template=new DeviceTemplate(templateId,"env","Environment",null,null,null,
                ProtocolType.MQTT,true,Map.of());
        when(sessions.findByRequestKey("request-1")).thenReturn(Optional.empty());
        when(templates.findByIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(members.findByTemplateIdOrderByDisplayOrder(templateId)).thenReturn(java.util.List.of());
        DeviceProvisioningService service=new DeviceProvisioningService(sessions,templates,members,bindings,devices,
                spaces,new BindingConstraintService(),new ObjectMapper(),Clock.systemUTC(),new FreshnessPolicy());

        assertThrows(IllegalArgumentException.class,()->service.provision(new ProvisionDeviceRequest("request-1",
                templateId,UUID.randomUUID(),"Device","SERIAL-1",DeviceMode.REAL,Map.of())));

        verify(templates).findByIdForUpdate(templateId);
        verify(templates,never()).findById(templateId);
        verify(members).findByTemplateIdOrderByDisplayOrder(templateId);
    }
}
