package com.zone.lesoongen.platform;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/optimizations")
public class PlatformOptimizeHistoryController {
    private final PlatformOptimizeService service;
    private final com.zone.lesoongen.auth.PlatformAccessService access;

    public PlatformOptimizeHistoryController(PlatformOptimizeService service,
            com.zone.lesoongen.auth.PlatformAccessService access) {
        this.service = service; this.access = access;
    }

    @GetMapping
    public List<PlatformOptimizeService.History> list() { return service.history(access.currentUserId()); }
}
