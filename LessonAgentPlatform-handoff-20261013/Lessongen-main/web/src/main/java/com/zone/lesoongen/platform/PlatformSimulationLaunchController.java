package com.zone.lesoongen.platform;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import com.zone.lesoongen.auth.PlatformAccessService;

@RestController
@RequestMapping("/api/platform/simulation-launches")
public class PlatformSimulationLaunchController {
    private final PlatformSimulationLaunchService launches;
    private final PlatformAccessService access;
    public PlatformSimulationLaunchController(PlatformSimulationLaunchService launches,
            PlatformAccessService access) { this.launches = launches; this.access = access; }

    public record LaunchRequest(@NotBlank String lessonId, @NotBlank String versionId,
            @NotNull UUID requestKey, List<String> activeRoles,
            @Min(1) @Max(300) Integer timeoutSeconds) {}
    public record MessageRequest(@NotBlank @Size(max = 10000) String message) {}
    public record SettingsRequest(@NotNull @Min(1) @Max(300) Integer timeoutSeconds) {}

    @PostMapping
    public ResponseEntity<PlatformSimulationLaunchService.Launch> start(@Valid @RequestBody LaunchRequest request) {
        access.requireOwnedLesson(request.lessonId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(launches.start(request));
    }

    @GetMapping
    public List<PlatformSimulationLaunchService.Launch> list() {
        return launches.list(access.currentUserId());
    }

    @GetMapping("/{launchId}")
    public PlatformSimulationLaunchService.Launch status(@PathVariable UUID launchId) {
        return launches.status(launchId);
    }

    @GetMapping("/{launchId}/classroom")
    public Map<?, ?> classroom(@PathVariable UUID launchId) {
        return launches.classroom(launchId);
    }

    @GetMapping("/{launchId}/events")
    public Map<?, ?> events(@PathVariable UUID launchId) {
        return launches.events(launchId);
    }

    @PostMapping("/{launchId}/messages")
    public Map<?, ?> message(@PathVariable UUID launchId, @Valid @RequestBody MessageRequest request) {
        return launches.message(launchId, request.message());
    }

    @PostMapping("/{launchId}/pause")
    public Map<?, ?> pause(@PathVariable UUID launchId) {
        return launches.control(launchId, "pause");
    }

    @PostMapping("/{launchId}/resume")
    public Map<?, ?> resume(@PathVariable UUID launchId) {
        return launches.control(launchId, "resume");
    }

    @PostMapping("/{launchId}/end")
    public Map<?, ?> end(@PathVariable UUID launchId) {
        return launches.control(launchId, "end");
    }

    @PatchMapping("/{launchId}/settings")
    public Map<?, ?> settings(@PathVariable UUID launchId, @Valid @RequestBody SettingsRequest request) {
        return launches.settings(launchId, request.timeoutSeconds());
    }
}
