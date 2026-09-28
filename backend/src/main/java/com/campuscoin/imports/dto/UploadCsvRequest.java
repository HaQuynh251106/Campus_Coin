package com.campuscoin.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A CSV file to parse and preview before importing (UC-11 A1).")
public record UploadCsvRequest(

        @Schema(description = "The file's name, as the student knows it. Stored and echoed so "
                + "uploads can be told apart; never opened or resolved as a path.",
                example = "september-expenses.csv")
        @NotBlank(message = "The file's name is required.")
        @Size(max = 255, message = "The file's name must be at most 255 characters.")
        String filename,

        @Schema(description = "The file's text, decoded as UTF-8. Must have a header row naming "
                + "its columns, including `date`, `amount` and `type`.",
                example = "date,amount,type,description,category\n2026-09-24,12.50,EXPENSE,Lunch,Food")

        @NotBlank(message = "The file is empty.")
        String content) {
}
