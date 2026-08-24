package com.bank.aml.controller;

import com.bank.aml.dossier.CaseDossier;
import com.bank.aml.dossier.CaseDossierService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 案件调查档案导出接口。 */
@RestController
@RequestMapping("/api/cases")
public class CaseDossierController {

    private final CaseDossierService service;

    public CaseDossierController(CaseDossierService service) {
        this.service = service;
    }

    @GetMapping("/{id}/dossier")
    public ResponseEntity<CaseDossier> dossier(@PathVariable Long id) {
        CaseDossier dossier = service.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=aml-case-" + id + "-dossier.json")
                .header("X-Content-SHA256", dossier.contentHash())
                .body(dossier);
    }
}
