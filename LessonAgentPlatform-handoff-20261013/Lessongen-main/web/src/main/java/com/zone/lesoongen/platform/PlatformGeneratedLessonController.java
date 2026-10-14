package com.zone.lesoongen.platform;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/lesson-jobs")
public class PlatformGeneratedLessonController {
    private final PlatformGeneratedLessonService service;
    public PlatformGeneratedLessonController(PlatformGeneratedLessonService service) { this.service = service; }
    @PostMapping("/{jobId}/platform-lesson")
    public PlatformGeneratedLessonService.Adopted adopt(@PathVariable String jobId) { return service.adopt(jobId); }
}
