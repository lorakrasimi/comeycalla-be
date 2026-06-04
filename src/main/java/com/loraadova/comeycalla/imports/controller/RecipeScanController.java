package com.loraadova.comeycalla.imports.controller;

import com.loraadova.comeycalla.imports.dto.RecipeImportRequest;
import com.loraadova.comeycalla.imports.ocr.service.RecipeImportImageService;
import com.loraadova.comeycalla.imports.dto.RecipeScanResponseDto;
import com.loraadova.comeycalla.imports.url.service.RecipeImportUrlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/recipes")
@CrossOrigin(origins = "http://localhost:4200")
public class RecipeScanController {

    @Autowired
    private RecipeImportImageService recipeScanService;

    @Autowired
    private RecipeImportUrlService recipeImportService;

    @PostMapping("/scan")
    public ResponseEntity<RecipeScanResponseDto> scanRecipeImages(
            @RequestParam("images") List<MultipartFile> images,
            @RequestParam(value = "sections", required = false) List<String> sections
    ) {
        if (images == null || images.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        RecipeScanResponseDto result = recipeScanService.scanImages(images, sections);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/import-url")
    public RecipeScanResponseDto importFromUrl(@RequestBody RecipeImportRequest request) {
        return this.recipeImportService.importFromUrl(request.url());
    }
}