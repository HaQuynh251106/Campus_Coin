package com.campuscoin.bookmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class BookmarksApiIT extends AbstractBookmarksApiIT {

    @Test
    @DisplayName("UC-19 B1: saving a tip returns the entry, with the tip's own words and nothing internal")
    void savingATipReturnsTheEntryWithTheTipsOwnWords() throws Exception {
        String token = loginNewStudent();
        JsonNode tip = generatedTip(token);

        JsonNode saved = saveTipExpectingCreated(token, tip.get("id").asLong());

        assertThat(fieldNamesOf(saved))
                .as("a saved entry must publish exactly the documented fields")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_BOOKMARK_FIELDS.stream()
                        .filter(field -> !field.equals("note"))
                        .toList());
        assertThat(saved.get("itemType").asText()).isEqualTo("TIP");
        assertThat(saved.get("tipId").asLong()).isEqualTo(tip.get("id").asLong());

        assertThat(saved.get("tipTitle").asText()).isEqualTo(tip.get("title").asText());
        assertThat(saved.get("tipBody").asText()).isEqualTo(tip.get("body").asText());
        assertThat(saved.get("tipPotentialSaving").decimalValue())
                .isEqualByComparingTo(tip.get("potentialSaving").decimalValue());
        assertThat(saved.get("tipState").asText()).isEqualTo("NEW");
        assertThat(saved.get("tipMonth").asText()).isEqualTo(asMonth(thisMonth()));

        assertThat(saved.has("note")).isFalse();
    }

    @Test
    @DisplayName("UC-19 B3: a saved item is still there in a later session")
    void aSavedItemSurvivesIntoAnotherSession() throws Exception {
        String email = randomEmail();
        register(email);
        String firstSession = login(email);

        Long tipId = generateATip(firstSession);
        Long bookmarkId = saveATipId(firstSession);
        assertThat(bookmarkId).isNotNull();

        String laterSession = login(email);
        assertThat(laterSession).isNotEqualTo(firstSession);

        JsonNode list = bookmarks(laterSession);
        assertThat(savedTipIdsOf(list)).containsExactly(tipId);
        assertThat(bookmarkIdsOf(list)).containsExactly(bookmarkId);
    }

    @Test
    @DisplayName("UC-19 B4: the list is newest first, and the order is stable for a tie")
    void theListIsNewestFirst() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        Long currentTip = generateATip(token);
        Long firstBookmark = saveATipId(token);

        LocalDate lastMonth = monthBefore(thisMonth());
        Long olderTip = generateATipFor(userId, lastMonth);
        JsonNode second = saveTipExpectingCreated(token, olderTip);

        JsonNode list = bookmarks(token);
        assertThat(savedTipIdsOf(list)).containsExactly(olderTip, currentTip);
        assertThat(bookmarkIdsOf(list))
                .as("the entry saved second leads the list")
                .startsWith(second.get("id").asLong(), firstBookmark);

        assertThat(list.get(0).get("tipMonth").asText()).isEqualTo(asMonth(lastMonth));
    }

    @Test
    @DisplayName("Encryption: a note is an envelope at rest and the student's words through the API")
    void aNoteIsCiphertextAtRestAndPlaintextThroughTheApi() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);

        String marker = "zzbookmarkmarker";
        String note = marker + " - the boba runs are the whole problem";

        JsonNode saved = saveTipExpectingCreated(token, tipId, note);
        Long bookmarkId = saved.get("id").asLong();

        String stored = storedNoteOf(bookmarkId);
        assertThat(stored).isNotNull().doesNotContain(marker).isNotEqualTo(note);

        assertThat(decryptField(stored)).isEqualTo(note);

        assertThat(saved.get("note").asText()).isEqualTo(note);
        assertThat(bookmarks(token).get(0).get("note").asText()).isEqualTo(note);

        JsonNode padded = saveTipExpectingCreated(token,
                generateATipFor(userIdOf(token), monthBefore(thisMonth())), "  padded note  ");
        assertThat(decryptField(storedNoteOf(padded.get("id").asLong()))).isEqualTo("padded note");
    }

    @Test
    @DisplayName("Encryption: two notes with the same words do not share ciphertext")
    void identicalNotesDoNotShareCiphertext() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        String note = "same words, different rows";

        Long first = saveATipId(token);
        JsonNode second = saveTipExpectingCreated(token, generateATipFor(userId, monthBefore(thisMonth())),
                note);

        ResponseEntity<String> firstEdit = setNote(token, first, note);
        assertThat(firstEdit.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(storedNoteOf(first))
                .isNotNull()
                .isNotEqualTo(storedNoteOf(second.get("id").asLong()));
    }

    @Test
    @DisplayName("UC-19 B2: a note of spaces stores no note at all, and the field is then omitted")
    void aBlankNoteStoresNoNote() throws Exception {
        String token = loginNewStudent();
        Long bookmarkId = saveATipId(token);

        ResponseEntity<String> response = setNote(token, bookmarkId, "   ");
        assertThat(response.getStatusCode()).as("body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(storedNoteOf(bookmarkId)).isNull();
        JsonNode updated = body(response);
        assertThat(updated.has("note")).isFalse();
        assertThat(fieldNamesOf(updated))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_BOOKMARK_FIELDS.stream()
                        .filter(field -> !field.equals("note"))
                        .toList());
    }

    @Test
    @DisplayName("UC-19 B2: absent and explicit null both mean \"leave the note alone\"")
    void anAbsentOrNullNoteLeavesTheNoteUnchanged() throws Exception {
        String token = loginNewStudent();
        Long bookmarkId = saveATipId(token);
        setNote(token, bookmarkId, "keep me");

        ResponseEntity<String> absent = send(HttpMethod.PATCH, BOOKMARKS_URL + "/" + bookmarkId,
                token, Map.of());
        assertThat(absent.getStatusCode()).as("absent body=%s", absent.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(absent.getBody()).contains("keep me");

        ResponseEntity<String> explicitNull = setNoteToNull(token, bookmarkId);
        assertThat(explicitNull.getStatusCode()).as("null body=%s", explicitNull.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(explicitNull.getBody()).contains("keep me");

        assertThat(decryptField(storedNoteOf(bookmarkId))).isEqualTo("keep me");
    }

    @Test
    @DisplayName("UC-19 B2: an empty string clears the note")
    void anEmptyStringClearsTheNote() throws Exception {
        String token = loginNewStudent();
        Long bookmarkId = saveATipId(token);
        setNote(token, bookmarkId, "temporary");

        JsonNode cleared = body(setNote(token, bookmarkId, ""));

        assertThat(cleared.has("note")).isFalse();
        assertThat(storedNoteOf(bookmarkId)).isNull();
    }

    @Test
    @DisplayName("UC-19 B2: editing a note keeps the saved time and the entry's place in the list")
    void editingANoteKeepsTheSavedTimeAndPlace() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        JsonNode first = saveATip(token);
        Long firstId = first.get("id").asLong();
        Long secondId = saveATipIdFor(token, generateATipFor(userId, monthBefore(thisMonth())));

        String createdAtBefore = columnInDatabase(firstId, "bookmarks", "created_at");
        List<Long> orderBefore = bookmarkIdsOf(bookmarks(token));

        assertThat(body(setNote(token, firstId, "a second thought")).get("note").asText())
                .isEqualTo("a second thought");

        assertThat(columnInDatabase(firstId, "bookmarks", "created_at"))
                .isEqualTo(createdAtBefore);
        assertThat(bookmarkIdsOf(bookmarks(token))).isEqualTo(orderBefore);
        assertThat(orderBefore).containsExactly(secondId, firstId);
    }

    @Test
    @DisplayName("UC-19 B2: a note longer than 255 characters is refused on both writes")
    void anOverlongNoteIsRefused() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);
        Long bookmarkId = saveATipId(token);
        String tooLong = "x".repeat(256);

        ResponseEntity<String> onSave = saveTip(token, tipId, tooLong);
        assertThat(onSave.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(onSave))).containsExactly("note");

        ResponseEntity<String> onEdit = setNote(token, bookmarkId, tooLong);
        assertThat(onEdit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(onEdit))).containsExactly("note");

        assertThat(saveTipExpectingCreated(token, generateATipFor(userIdOf(token), monthBefore(thisMonth())),
                "line one\nline two").get("note").asText()).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("Section 7.6: neither the plaintext note nor the envelope reaches the log")
    void aNoteNeverReachesTheLog(CapturedOutput output) throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);

        String marker = "zzplaintextnotemarker";
        String note = marker + " - what I actually typed";

        JsonNode saved = saveTipExpectingCreated(token, tipId, note);
        Long bookmarkId = saved.get("id").asLong();
        String envelope = storedNoteOf(bookmarkId);

        setNote(token, bookmarkId, note);
        assertThat(bookmarks(token).get(0).get("note").asText()).isEqualTo(note);

        String log = output.getOut() + output.getErr();
        assertThat(log)
                .as("the note a student typed must never be logged")
                .doesNotContain(marker)
                .doesNotContain(note);

        assertThat(log)
                .as("the stored envelope must not be logged either")
                .doesNotContain(envelope);
    }

    @Test
    @DisplayName("UC-19 B4: un-marking removes the entry and leaves the tip alone")
    void unmarkingRemovesTheEntryAndLeavesTheTip() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);
        Long bookmarkId = saveATipId(token);

        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookmarks(token)).isEmpty();

        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE id = ?", bookmarkId)).isZero();
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE id = ?", tipId)).isEqualTo(1);
        assertThat(changeTipState(token, tipId, "DISMISSED").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-19 B4: un-marking something already gone succeeds, so a retry is not a failure")
    void unmarkingIsIdempotent() throws Exception {
        String token = loginNewStudent();
        Long bookmarkId = saveATipId(token);

        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE id = ?", bookmarkId)).isZero();
    }

    @Test
    @DisplayName("BR-02: un-marking another student's entry leaves it in place, without revealing it exists")
    void unmarkingSomebodyElsesEntryIsANoOp() throws Exception {
        String ownerToken = loginNewStudent();
        Long bookmarkId = saveATipId(ownerToken);
        Long tipId = savedTipIdsOf(bookmarks(ownerToken)).get(0);

        String otherToken = loginNewStudent();
        ResponseEntity<String> response = unmark(otherToken, bookmarkId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE id = ?", bookmarkId)).isEqualTo(1);
        assertThat(bookmarksOwner(bookmarkId)).isEqualTo(userIdOf(ownerToken));
        assertThat(savedTipIdsOf(bookmarks(ownerToken))).containsExactly(tipId);
    }

    @Test
    @DisplayName("BR-02: a tip belonging to another student answers the same 404 as one that does not exist")
    void anotherStudentsTipIsIndistinguishableFromAMissingOne() throws Exception {
        String ownerToken = loginNewStudent();
        Long foreignTipId = generateATip(ownerToken);

        String strangerToken = loginNewStudent();
        ResponseEntity<String> foreign = saveTip(strangerToken, foreignTipId);
        ResponseEntity<String> missing = saveTip(strangerToken, 999_999_999L);

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(foreign)).isEqualTo("NOT_FOUND");
        assertThat(errorCodeOf(missing)).isEqualTo("NOT_FOUND");
        assertThat(body(foreign).get("message").asText())
                .isEqualTo(body(missing).get("message").asText());

        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ?",
                userIdOf(strangerToken))).isZero();
        assertThat(foreign.getBody())
                .doesNotContain("BR-02", "SIGNAL", "trg_bookmarks", "SQLSTATE", "fk_bookmark_tip");
    }

    @Test
    @DisplayName("BR-02: one student's saved list is not reachable from another student's token")
    void oneStudentsListIsNotAnothers() throws Exception {
        String ownerToken = loginNewStudent();
        Long bookmarkId = saveATipId(ownerToken);
        Long ownerId = userIdOf(ownerToken);

        String strangerToken = loginNewStudent();

        assertThat(bookmarks(strangerToken)).isEmpty();

        ResponseEntity<String> edit = setNote(strangerToken, bookmarkId, "not mine");
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE id = ? AND user_id = ?",
                bookmarkId, ownerId)).isEqualTo(1);
        assertThat(storedNoteOf(bookmarkId)).isNull();
    }

    @Test
    @DisplayName("UC-19 B1: saving the same tip twice is refused as a conflict, not silently accepted")
    void savingTheSameTipTwiceIsAConflict() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);
        saveTipExpectingCreated(token, tipId);

        ResponseEntity<String> second = saveTip(token, tipId, "a note with it this time");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(second)).isEqualTo("BOOKMARK_ALREADY_EXISTS");
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ? AND tip_id = ?",
                userIdOf(token), tipId)).isEqualTo(1);

        assertThat(second.getBody()).doesNotContain("uk_bookmark_dedupe", "dedupe_key", "Duplicate");
    }

    @Test
    @DisplayName("UC-19 B1: an insight is refused by name, with the reason, rather than silently ignored")
    void anInsightIsRefusedByName() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);

        ResponseEntity<String> response = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemType", "INSIGHT", "itemId", tipId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesIn(body(response))).containsExactly("itemType");
        assertThat(response.getBody())
                .contains("UC-17")
                .doesNotContain("Insight cannot be null", "No enum constant");

        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ?",
                userIdOf(token))).isZero();
        assertThat(bookmarks(token)).isEmpty();
    }

    @Test
    @DisplayName("UC-19 B1: a request naming no item, or an impossible one, is a field error")
    void anIncompleteTargetIsAFieldError() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> noItem = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemType", "TIP"));
        assertThat(noItem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(noItem))).containsExactly("itemId");

        ResponseEntity<String> noKind = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemId", 1));
        assertThat(noKind.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(noKind))).containsExactly("itemType");

        for (int impossible : new int[] {0, -1}) {
            ResponseEntity<String> response = send(HttpMethod.POST, BOOKMARKS_URL, token,
                    Map.of("itemType", "TIP", "itemId", impossible));
            assertThat(response.getStatusCode()).as("itemId=%d body=%s", impossible, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fieldNamesIn(body(response))).containsExactly("itemId");
        }
    }

    @Test
    @DisplayName("VĐ-03: a tip dismissed on the tips screen stays in the saved list")
    void aDismissedTipStaysInTheSavedList() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);
        Long bookmarkId = saveATipId(token);

        assertThat(changeTipState(token, tipId, "DISMISSED").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(tipsAfterGenerating(token)).isEmpty();

        JsonNode list = bookmarks(token);

        assertThat(bookmarkIdsOf(list)).containsExactly(bookmarkId);
        assertThat(list.get(0).get("tipState").asText()).isEqualTo("DISMISSED");
        assertThat(tipStateOf(tipId)).isEqualTo("DISMISSED");

        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookmarks(token)).isEmpty();
    }

    @Test
    @DisplayName("Section 7.5: an administrator token is refused, and no token is unauthenticated")
    void administratorsAreRefusedAndAnonymousCallersAreNotAuthenticated() throws Exception {
        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, BOOKMARKS_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, BOOKMARKS_URL, adminToken,
                Map.of("itemType", "TIP", "itemId", 1)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.PATCH,
                HttpMethod.DELETE}) {
            String url = method == HttpMethod.GET ? BOOKMARKS_URL : BOOKMARKS_URL + "/1";
            assertThat(send(method, url, null, method == HttpMethod.PATCH ? Map.of() : null)
                    .getStatusCode())
                    .as("%s %s without a token", method, url)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("Section 13: a missing route is a 404 while a supported path with the wrong method is a 400")
    void unroutedPathsAndWrongMethodsFailDifferently() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> read = send(HttpMethod.GET, BOOKMARKS_URL + "/1", token, null);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(read)).isEqualTo("INVALID_REQUEST");
        assertThat(body(read).get("message").asText())
                .isEqualTo("The HTTP method is not supported by this endpoint.");

        ResponseEntity<String> clearAll = send(HttpMethod.DELETE, BOOKMARKS_URL, token, null);
        assertThat(clearAll.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(clearAll)).isEqualTo("INVALID_REQUEST");

        for (String absent : new String[] {BOOKMARKS_URL + "/1/note", BOOKMARKS_URL + "/1/pin",
                BOOKMARKS_URL + "/1/insights"}) {
            ResponseEntity<String> response = send(HttpMethod.PATCH, absent, token, Map.of());
            assertThat(response.getStatusCode()).as("PATCH %s", absent)
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCodeOf(response)).as("PATCH %s", absent).isEqualTo("NOT_FOUND");
        }
    }

    private Long saveATipIdFor(String token, Long tipId) throws Exception {
        return saveTipExpectingCreated(token, tipId).get("id").asLong();
    }

    private JsonNode tipsAfterGenerating(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, TIPS_GENERATE_URL, token, null);
        assertThat(response.getStatusCode()).as("generate body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("tips");
    }
}
