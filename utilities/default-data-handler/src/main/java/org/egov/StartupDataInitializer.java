package org.egov;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import org.egov.handler.util.LocalizationUtil;
import org.egov.handler.util.MdmsBulkLoader;
import org.egov.handler.web.models.User;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StreamUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.egov.common.contract.request.RequestInfo;
import org.egov.handler.service.DataHandlerService;
import org.egov.handler.web.models.DefaultDataRequest;
import org.egov.handler.web.models.Tenant;
import org.egov.handler.web.models.TenantRequest;
import org.egov.handler.config.ServiceConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
//@Profile("init")
@RequiredArgsConstructor
public class StartupDataInitializer{

    private final DataHandlerService dataHandlerService;

    private final ServiceConfiguration serviceConfig;

    private final ResourceLoader resourceLoader;

    private final ObjectMapper objectMapper;

    private final MdmsBulkLoader mdmsBulkLoader;

    private final LocalizationUtil localizationUtil;

    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    // Delay 1 minutes after app startup
    @Scheduled(initialDelay =  1* 10 * 1000, fixedDelay = Long.MAX_VALUE)
    public void runOnceAfterStartup() {
        if (hasRun.get()) return;

        System.out.println("[DEBUG] Delayed startup logic executing at: " + Instant.now());
        try {
            executeStartupLogic();
            hasRun.set(true);
        } catch (Exception e) {
            System.err.println("StartupDataInitializer failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

//    @EventListener(ApplicationReadyEvent.class)
//    public void onApplicationReady() {
//        System.out.println("[DEBUG] ApplicationReadyEvent triggered");
//
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//        scheduler.schedule(this::executeStartupLogic, 10, TimeUnit.SECONDS);
//    }

    public void executeStartupLogic() throws Exception {
        System.out.println("[DEBUG] Startup logic executing at: " + Instant.now());
        try {
            String tenantCode = serviceConfig.getDefaultTenantId();

            Resource resource = resourceLoader.getResource("classpath:requestInfo.json");
            Resource tenantJson = resourceLoader.getResource("classpath:tenant.json");

            String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

            String jsonTenant = StreamUtils.copyToString(tenantJson.getInputStream(), StandardCharsets.UTF_8);

            json = json.replace("{tenantid}", tenantCode);
            jsonTenant = jsonTenant.replace("{tenantid}", tenantCode);

            Tenant tenant = objectMapper.readValue(jsonTenant, Tenant.class);

            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode requestInfoNode = rootNode.get("RequestInfo");

            if (requestInfoNode == null) {
                throw new RuntimeException("Missing 'RequestInfo' node in JSON");
            }
            RequestInfo requestInfo = objectMapper.readValue(requestInfoNode.toString(), RequestInfo.class);

            TenantRequest tenantRequest = TenantRequest.builder()
                    .requestInfo(requestInfo)
                    .tenant(tenant)
                    .build();

            DefaultDataRequest defaultDataRequest = DefaultDataRequest.builder().requestInfo(tenantRequest.getRequestInfo()).targetTenantId(tenantCode).schemaCodes(serviceConfig.getDefaultMdmsSchemaList()).onlySchemas(Boolean.FALSE).locales(serviceConfig.getDefaultLocalizationLocaleList()).modules(serviceConfig.getDefaultLocalizationModuleList()).build();
            defaultDataRequest.setTargetTenantId(tenantCode);
            //Create Schema
            dataHandlerService.createMdmsSchemaFromFile(defaultDataRequest);
            // Load mdms data
            mdmsBulkLoader.loadAllMdmsData(defaultDataRequest.getTargetTenantId(), defaultDataRequest.getRequestInfo());
            // create Boundary Data
            dataHandlerService.createBoundaryDataFromFile(defaultDataRequest);
            // upsert localization
            localizationUtil.upsertLocalizationFromFile(defaultDataRequest);
            // create User
            dataHandlerService.createUserFromFile(tenantRequest);

            dataHandlerService.createPgrWorkflowConfig(tenantRequest.getTenant().getCode());
            // create Employee
            dataHandlerService.createEmployeeFromFile(defaultDataRequest.getRequestInfo());

//            dataHandlerService.createTenantConfig(tenantRequest);
        }
        catch (Exception e) {
            System.err.println("StartupDataInitializer failed: " + e.getMessage());
            e.printStackTrace();
        }

    }
}
