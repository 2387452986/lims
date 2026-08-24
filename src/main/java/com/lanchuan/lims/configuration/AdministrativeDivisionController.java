package com.lanchuan.lims.configuration;

import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/administrative-divisions")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class AdministrativeDivisionController {
    private final AdministrativeDivisionService service;

    public AdministrativeDivisionController(AdministrativeDivisionService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdministrativeDivisionService.Option> children(
            @RequestParam(defaultValue = "") @Size(max = 80) String province,
            @RequestParam(defaultValue = "") @Size(max = 80) String city) {
        return service.children(province, city);
    }
}
