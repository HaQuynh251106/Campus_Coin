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

/**
 * UC-19 against the real API and a real MySQL 8.
 *
 * <p>What is asserted here is the behaviour a student would notice: they save one of their own tips
 * and can look at it again in a later session, they can write a note about it and change their mind,
 * they can let an item go, and nothing they do reaches anyone else's list. The advice itself - which
 * tips exist and what they say - is module 9's and is not re-tested here; the fixtures generate a real
 * tip and this suite holds the saved copy to it.
 *
 * <p><b>VĐ-03 is the distinction this suite keeps proving.</b> "Pin" and "bookmark" are two acts on
 * two rows: pinning is {@code user_tips.state} and determines display order, bookmarking is a row in
 * {@code bookmarks} and determines what survives. The tests below dismiss a tip that was saved and
 * show the saved entry stays, because a student who chose to keep something has asked for exactly
 * that.
 *
 * <p>Each test registers its own student, so nothing depends on another test's rows and the seeded
 * student's own bookmarks - if any - are left untouched.
 */
@ExtendWith(OutputCaptureExtension.class)
class BookmarksApiIT extends AbstractBookmarksApiIT {

    // ==================================================================
    //  UC-19 B1/B3 - saving, and finding it again
    // ==================================================================

    @Test
    @DisplayName("UC-19 B1: saving a tip returns the entry, with the tip's own words and nothing internal")
    void savingATipReturnsTheEntryWithTheTipsOwnWords() throws Exception {
        String token = loginNewStudent();
        JsonNode tip = generatedTip(token);

        JsonNode saved = saveTipExpectingCreated(token, tip.get("id").asLong());

        // Every documented field except "note", which is optional and therefore absent here - the
        // line further down pins that absence, and asserting it twice would say the same thing twice.
        assertThat(fieldNamesOf(saved))
                .as("a saved entry must publish exactly the documented fields")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_BOOKMARK_FIELDS.stream()
                        .filter(field -> !field.equals("note"))
                        .toList());
        assertThat(saved.get("itemType").asText()).isEqualTo("TIP");
        assertThat(saved.get("tipId").asLong()).isEqualTo(tip.get("id").asLong());
        // The card travels with the bookmark, so a client renders it from this one response rather
        // than resolving the tip id per row. The words are the database's - asserted equal to the
        // tips endpoint's own answer, not re-derived here.
        assertThat(saved.get("tipTitle").asText()).isEqualTo(tip.get("title").asText());
        assertThat(saved.get("tipBody").asText()).isEqualTo(tip.get("body").asText());
        assertThat(saved.get("tipPotentialSaving").decimalValue())
                .isEqualByComparingTo(tip.get("potentialSaving").decimalValue());
        assertThat(saved.get("tipState").asText()).isEqualTo("NEW");
        assertThat(saved.get("tipMonth").asText()).isEqualTo(asMonth(thisMonth()));
        // No note was sent, so the field is absent rather than null: "none" and "empty" are different
        // answers and a client has to be able to tell them apart.
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

        // A new sign-in is a new session row and a new token; the postcondition UC-19 states is that
        // the marked item is reachable "in later sessions", which is what this proves.
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

        // A second, distinct tip: uk_bookmark_dedupe refuses the same item twice, and the generate
        // endpoint only ever runs for the current month, so the second comes from the same procedure
        // for a named month - the route module 9's fixtures document.
        LocalDate lastMonth = monthBefore(thisMonth());
        Long olderTip = generateATipFor(userId, lastMonth);
        JsonNode second = saveTipExpectingCreated(token, olderTip);

        JsonNode list = bookmarks(token);
        assertThat(savedTipIdsOf(list)).containsExactly(olderTip, currentTip);
        assertThat(bookmarkIdsOf(list))
                .as("the entry saved second leads the list")
                .startsWith(second.get("id").asLong(), firstBookmark);

        // Both rows may share a created_at, because DATETIME has second precision and the two calls
        // happen in the same second. The ordering is still deterministic: `id` is the tie-break, so
        // two calls of the same endpoint cannot return the same pair in two orders.
        assertThat(list.get(0).get("tipMonth").asText()).isEqualTo(asMonth(lastMonth));
    }

    // ==================================================================
    //  UC-19 B2 - the note
    // ==================================================================

    @Test
    @DisplayName("Encryption: a note is an envelope at rest and the student's words through the API")
    void aNoteIsCiphertextAtRestAndPlaintextThroughTheApi() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);

        // A word that appears nowhere in the seed or any other test, so a hit in the column or in the
        // log can only have come from this value.
        String marker = "zzbookmarkmarker";
        String note = marker + " - the boba runs are the whole problem";

        JsonNode saved = saveTipExpectingCreated(token, tipId, note);
        Long bookmarkId = saved.get("id").asLong();

        // The direct SELECT an attacker with the database file runs. It must not reveal the words.
        String stored = storedNoteOf(bookmarkId);
        assertThat(stored).isNotNull().doesNotContain(marker).isNotEqualTo(note);
        // ...and it must be a real envelope, not merely an encoding: the application recovers the text.
        assertThat(decryptField(stored)).isEqualTo(note);

        // The owner still sees it, from both reads that publish a bookmark.
        assertThat(saved.get("note").asText()).isEqualTo(note);
        assertThat(bookmarks(token).get(0).get("note").asText()).isEqualTo(note);

        // The trim is only observable after decryption, which is itself the proof that the round trip
        // happened in the database rather than in the service's head.
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

        // The first bookmark was saved without a note, so set the same words on both. A fresh IV per
        // encryption is what stops an attacker recognising, from the ciphertext alone, that two
        // students - or two rows - carry the same note.
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

        // "You wrote spaces" and "you wrote nothing" are the same thing to a reader, and NULL is the
        // honest form of that - not an envelope around an empty string, which would be a
        // distinguishable value carrying no meaning.
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

        // A JSON body has only these two spellings for "I did not touch this", and a client written in
        // any language has to be able to express it. Both must reach the service as "leave it".
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

        // Clearing needs a spelling of its own, and the empty string is it - the same convention
        // UpdateCategoryRequest uses.
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

        // The reason this is a PATCH and not a remove-and-save-again. Re-creating the entry would give
        // it a new saved time and move it to the top of a list ordered by when things were saved -
        // and would re-fire a trigger whose job is to make a bookmark's target unchangeable.
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

        // 256 characters cannot fit VARCHAR(255), and the bound is the plaintext limit - the column is
        // wider only because an envelope is longer than what it protects.
        ResponseEntity<String> onSave = saveTip(token, tipId, tooLong);
        assertThat(onSave.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(onSave))).containsExactly("note");

        ResponseEntity<String> onEdit = setNote(token, bookmarkId, tooLong);
        assertThat(onEdit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(onEdit))).containsExactly("note");

        // A note with a line break is accepted: the bound is on length, not on shape.
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

        // Exercise the read paths too: an edit and a list are the two places the plaintext genuinely
        // exists in the process and could be logged by accident.
        setNote(token, bookmarkId, note);
        assertThat(bookmarks(token).get(0).get("note").asText()).isEqualTo(note);

        String log = output.getOut() + output.getErr();
        assertThat(log)
                .as("the note a student typed must never be logged")
                .doesNotContain(marker)
                .doesNotContain(note);

        // Nor the ciphertext. An envelope in a log is useless without the key, but it is a value that
        // belongs in the database and nowhere else, and the log line the service writes names the ids
        // rather than the value for exactly this reason.
        assertThat(log)
                .as("the stored envelope must not be logged either")
                .doesNotContain(envelope);
    }

    // ==================================================================
    //  UC-19 B4 - letting an item go
    // ==================================================================

    @Test
    @DisplayName("UC-19 B4: un-marking removes the entry and leaves the tip alone")
    void unmarkingRemovesTheEntryAndLeavesTheTip() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);
        Long bookmarkId = saveATipId(token);

        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookmarks(token)).isEmpty();

        // The advice stays on the tips screen: only this student's entry in their own list is removed.
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
        // "It is not in my list" is the end state the caller asked for, so a retry or two devices
        // acting at once settle on it rather than reporting an error - the treatment a second logout
        // gets.
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

        // Answered exactly as the caller's own already-removed entry is, so the endpoint cannot be
        // used to discover which bookmark identifiers exist.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // And the row survives, still the owner's.
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE id = ?", bookmarkId)).isEqualTo(1);
        assertThat(bookmarksOwner(bookmarkId)).isEqualTo(userIdOf(ownerToken));
        assertThat(savedTipIdsOf(bookmarks(ownerToken))).containsExactly(tipId);
    }

    // ==================================================================
    //  What a bookmark points at
    // ==================================================================

    @Test
    @DisplayName("BR-02: a tip belonging to another student answers the same 404 as one that does not exist")
    void anotherStudentsTipIsIndistinguishableFromAMissingOne() throws Exception {
        String ownerToken = loginNewStudent();
        Long foreignTipId = generateATip(ownerToken);

        String strangerToken = loginNewStudent();
        ResponseEntity<String> foreign = saveTip(strangerToken, foreignTipId);
        ResponseEntity<String> missing = saveTip(strangerToken, 999_999_999L);

        // 45000 is the trigger refusing a tip that is not the caller's; 23000 on fk_bookmark_tip is a
        // tip that is not there. A caller told which of the two happened could enumerate other
        // students' tip identifiers one request at a time (section 7.5), so both are reported alike.
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(foreign)).isEqualTo("NOT_FOUND");
        assertThat(errorCodeOf(missing)).isEqualTo("NOT_FOUND");
        assertThat(body(foreign).get("message").asText())
                .isEqualTo(body(missing).get("message").asText());

        // Nothing was written, and the trigger's own words and the constraint name are not forwarded.
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
        // No endpoint takes a user id, so the only list reachable is the caller's own - and it is
        // empty. This is what makes reading another student's jottings impossible rather than refused.
        assertThat(bookmarks(strangerToken)).isEmpty();

        // Nor can the stranger's own entry be the owner's row: a note edit addressed to an entry that
        // is not theirs is a 404 rather than a write.
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

        // Answering 201 with the existing row would tell the caller a bookmark was created when none
        // was - and would discard the note this request carried, leaving them believing it was stored.
        // The remedy is named instead: the item is already in the list, and PATCH changes its note.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(second)).isEqualTo("BOOKMARK_ALREADY_EXISTS");
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ? AND tip_id = ?",
                userIdOf(token), tipId)).isEqualTo(1);
        // The dedupe key's name is an internal identifier and is not forwarded.
        assertThat(second.getBody()).doesNotContain("uk_bookmark_dedupe", "dedupe_key", "Duplicate");
    }

    @Test
    @DisplayName("UC-19 B1: an insight is refused by name, with the reason, rather than silently ignored")
    void anInsightIsRefusedByName() throws Exception {
        String token = loginNewStudent();
        Long tipId = generateATip(token);

        ResponseEntity<String> response = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemType", "INSIGHT", "itemId", tipId));

        // INSIGHT is a real value of the column, so narrowing the enum would answer with a JSON
        // parsing failure saying the value is invalid - which is both untrue and unhelpful. It is
        // refused where the scope decision can be explained, the treatment module 9 gives
        // LOW_SAVINGS_RATE.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesIn(body(response))).containsExactly("itemType");
        assertThat(response.getBody())
                .contains("UC-17")
                .doesNotContain("Insight cannot be null", "No enum constant");

        // Nothing was written: the refusal happens before the database is asked anything.
        assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ?",
                userIdOf(token))).isZero();
        assertThat(bookmarks(token)).isEmpty();
    }

    @Test
    @DisplayName("UC-19 B1: a request naming no item, or an impossible one, is a field error")
    void anIncompleteTargetIsAFieldError() throws Exception {
        String token = loginNewStudent();

        // Nothing to save at all.
        ResponseEntity<String> noItem = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemType", "TIP"));
        assertThat(noItem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(noItem))).containsExactly("itemId");

        // No kind named: which column the id belongs in is part of the request, and a server that
        // defaulted it would be guessing what the client meant.
        ResponseEntity<String> noKind = send(HttpMethod.POST, BOOKMARKS_URL, token,
                Map.of("itemId", 1));
        assertThat(noKind.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(noKind))).containsExactly("itemType");

        // A non-positive identifier cannot exist, so it is refused before a pointless round trip
        // rather than reported later as a missing tip.
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
        // BR-14's dismissal rule is applied where the tips are read; the saved list is a different
        // question with a different answer.
        assertThat(tipsAfterGenerating(token)).isEmpty();

        JsonNode list = bookmarks(token);
        // Keeping an item and displaying it are different acts. Filtering the row out here would
        // remove something the student did not remove, and the count they see would disagree with the
        // number of removals it takes to empty the list.
        assertThat(bookmarkIdsOf(list)).containsExactly(bookmarkId);
        assertThat(list.get(0).get("tipState").asText()).isEqualTo("DISMISSED");
        assertThat(tipStateOf(tipId)).isEqualTo("DISMISSED");

        // And B4's remedy still works on it.
        assertThat(unmark(token, bookmarkId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookmarks(token)).isEmpty();
    }

    // ==================================================================
    //  Section 7.5 - who may reach this
    // ==================================================================

    @Test
    @DisplayName("Section 7.5: an administrator token is refused, and no token is unauthenticated")
    void administratorsAreRefusedAndAnonymousCallersAreNotAuthenticated() throws Exception {
        String adminToken = adminLogin();

        // A saved entry is the same readable prose about one student's spending that the tips rule
        // protects, plus text the student typed. There is no administrative counterpart view or
        // operation on `bookmarks`, so refusing the role costs nothing.
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

    // ==================================================================
    //  What routing, as opposed to the handler, refuses
    // ==================================================================

    /**
     * A URL that is not routed and a URL that is routed for a different method fail differently, and
     * the difference is the contract.
     *
     * <p>{@code /bookmarks/{id}} is the only place in this API where a path exists for some methods and
     * not others - it serves {@code PATCH} and {@code DELETE} but deliberately has no {@code GET},
     * because a saved entry is read as part of the ordered list. That makes it the one path where
     * "this route does not exist" and "this route refuses this method" can be told apart, and both
     * {@code MODULE_09_MANUAL_TEST.md} and {@code MODULE_10_MANUAL_TEST.md} state which answer a
     * tester should see for a spread of invented URLs. Prose alone would not notice if Spring's
     * resolver behaviour or the two exception handlers drifted, so the distinction is pinned here.
     *
     * <p>The rule: a method that is not routed to any path is answered {@code 404 NOT_FOUND} by
     * {@code NoResourceFoundException}; a method that is refused on a path that <em>is</em> routed is
     * answered {@code 400 INVALID_REQUEST} by {@code HttpRequestMethodNotSupportedException}, whose
     * message says the method is not supported by the endpoint. Neither reveals whether a resource
     * exists - the {@code 400} is emitted before any handler runs - so no information about another
     * student's rows leaks through either.
     */
    @Test
    @DisplayName("Section 13: a missing route is a 404 while a supported path with the wrong method is a 400")
    void unroutedPathsAndWrongMethodsFailDifferently() throws Exception {
        String token = loginNewStudent();

        // A method this path does not serve. The path exists - for PATCH and DELETE - so the refusal
        // is a statement about the method, not about the path.
        ResponseEntity<String> read = send(HttpMethod.GET, BOOKMARKS_URL + "/1", token, null);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(read)).isEqualTo("INVALID_REQUEST");
        assertThat(body(read).get("message").asText())
                .isEqualTo("The HTTP method is not supported by this endpoint.");

        // The collection exists for GET and POST, so DELETE on it is the same kind of refusal.
        ResponseEntity<String> clearAll = send(HttpMethod.DELETE, BOOKMARKS_URL, token, null);
        assertThat(clearAll.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(clearAll)).isEqualTo("INVALID_REQUEST");

        // No path is routed here at all - a note is a PATCH of a field, not a sub-resource - so these
        // fail at path matching and are a true 404.
        for (String absent : new String[] {BOOKMARKS_URL + "/1/note", BOOKMARKS_URL + "/1/pin",
                BOOKMARKS_URL + "/1/insights"}) {
            ResponseEntity<String> response = send(HttpMethod.PATCH, absent, token, Map.of());
            assertThat(response.getStatusCode()).as("PATCH %s", absent)
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCodeOf(response)).as("PATCH %s", absent).isEqualTo("NOT_FOUND");
        }
    }

    // ==================================================================
    //  Helpers local to this suite
    // ==================================================================

    /** Saves a generated tip for a month the generate endpoint cannot reach. */
    private Long saveATipIdFor(String token, Long tipId) throws Exception {
        return saveTipExpectingCreated(token, tipId).get("id").asLong();
    }

    /**
     * The caller's current-month tips after running the generator.
     *
     * <p>Used to show the tips screen and the saved list disagree on purpose: the generator is asked
     * for its list rather than the read being trusted to be current, so "the dismissed tip is gone"
     * is a statement about what the screen shows now.
     */
    private JsonNode tipsAfterGenerating(String token) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, TIPS_GENERATE_URL, token, null);
        assertThat(response.getStatusCode()).as("generate body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return body(response).get("tips");
    }
}
