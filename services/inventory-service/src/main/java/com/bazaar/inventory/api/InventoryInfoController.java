package com.bazaar.inventory.api;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryInfoController {

    private final BuildProperties buildProperties;

    public InventoryInfoController(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/info")
    public ServiceInfoResponse info() {
        return new ServiceInfoResponse(
                buildProperties.getArtifact(),
                buildProperties.getVersion(),
                "UP");
    }
}
