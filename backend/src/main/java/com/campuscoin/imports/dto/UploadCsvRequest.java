package com.campuscoin.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/imports} (UC-11 A1): upload a file to preview.
 *
 * <p><b>The file arrives as text in a JSON body, not as a multipart upload, and that is the decision
 * this module's transport rests on.</b> The three things a multipart route would need are all absent
 * from this build: there is no {@code MultipartFile} anywhere in the codebase, no
 * {@code spring.servlet.multipart} block in {@code application.yml}, and no
 * {@code MaxUploadSizeExceededException} handler in {@code GlobalExceptionHandler}. Adding a multipart
 * route would therefore mean adding configuration and an error handler for one endpoint, and would
 * leave the over-size case answered by Spring's default rather than by this API's error contract. A
 * CSV is text, a student's export is tens of kilobytes, and a JSON string carries it exactly. The
 * route is {@code POST /api/v1/imports} as a consequence: a {@code /imports/csv} child path would
 * suggest a family of import formats this build does not have.
 *
 * <p><b>{@code content} is the file, decoded as UTF-8 by the client.</b> The parser reads text and
 * never bytes, so a client that sends a file in another encoding has already made a decision this API
 * cannot undo - which is why the field's description says UTF-8 rather than leaving it to be assumed.
 * A byte-order mark is still handled by the parser, because Excel writes one even in a UTF-8 export.
 *
 * <p><b>{@code filename} is stored and echoed; it is not a path and is never opened.</b> It goes into
 * {@code import_batches.original_filename} ({@code VARCHAR(255)}) so the student can tell two uploads
 * apart on their own list, and the response returns it. Nothing on this server resolves it, so a
 * value containing a path separator or a traversal sequence is not a vulnerability - but it is also
 * not useful, and the bound is the column's width because a longer name would be refused by the
 * database rather than by a field error.
 *
 * <p><b>Deliberately absent:</b>
 *
 * <ul>
 *   <li>{@code userId} - the owner is the account in the bearer token (BR-02).</li>
 *   <li>{@code fileHash} - computed here from {@code content}, so a client cannot claim a file is one
 *       the server has seen before.</li>
 *   <li>{@code status}, and the five counters - all of them are outcomes of the preview, decided by
 *       this server. A batch a client could describe would let it claim rows were valid before the
 *       parser had read them.</li>
 *   <li>Per-row category choices - a preview is what a student corrects, and corrections go to the
 *       row's own endpoint once the rows have ids.</li>
 * </ul>
 */
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
        // The content is required but its size is bounded in the service rather than by @Size: the
        // bound is a statement about how many rows this server will store, and the parser is what
        // counts them. A @Size here would measure characters, which is not the same question.
        @NotBlank(message = "The file is empty.")
        String content) {
}
