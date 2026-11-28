package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.service.BusinessException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.portfolio.worksystem.service.PortfolioStorageBudget.LimitExceededException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> demoStorageFull(
            com.portfolio.worksystem.service.PortfolioStorageBudget.LimitExceededException e) {
        return org.springframework.http.ResponseEntity.status(413)
                .body(java.util.Map.of("success", false, "message", e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error/business";
    }
}
