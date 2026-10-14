package com.zone.lesoongen.platform;


import org.springframework.web.bind.annotation.*;

import java.util.Map;


@RestController
@RequestMapping("/api/platform/workflows")
public class WorkflowSessionController {


    private final WorkflowSessionService service;

    private final WorkflowSessionStore store;



    public WorkflowSessionController(
            WorkflowSessionService service,
            WorkflowSessionStore store
    ){

        this.service = service;
        this.store = store;

    }



    @PostMapping("/session")
    public Map create(
            @RequestBody Map body
    ){
        throw new com.zone.lesoongen.application.AppException(
                org.springframework.http.HttpStatus.GONE,
                "LEGACY_WORKFLOW_ENDPOINT_DISABLED",
                "旧工作流会话入口已停用，请从工作流页面选择教案后进入对应功能");

    }

}
