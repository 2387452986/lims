package com.lanchuan.lims.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/change-password", "/workbench", "/modules/**", "/flow/**"})
    public String frontend() {
        return "forward:/index.html";
    }

    @GetMapping("/favicon.ico")
    public String favicon() {
        return "redirect:/brand/institution-mark.svg";
    }
}
