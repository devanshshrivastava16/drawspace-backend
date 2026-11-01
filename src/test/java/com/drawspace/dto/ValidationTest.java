package com.drawspace.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Bean Validation tests for the REST request DTOs (CONTRACT §3): the {@code displayName}
 * 2–24 rule on {@link CreateRoomRequest}/{@link JoinRoomRequest}, the optional
 * {@code roomTitle ≤ 60} rule, and the {@code @NotBlank} identity/secret fields used by
 * privileged actions (leave / clear canvas).
 *
 * <p>Runs against the default Jakarta Validator (Hibernate Validator, shipped with
 * {@code spring-boot-starter-validation}) — no Spring context. Note: no DTO in this
 * project declares {@code @Pattern} hex constraints; color validation is semantic and
 * lives in {@code OperationValidator} (§7), so only {@code @NotBlank}/{@code @Size}
 * constraints exist here to test.</p>
 */
class ValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ------------------------------------------------------------------
    // CreateRoomRequest (§3 #1)
    // ------------------------------------------------------------------

    @Test
    void createRoomRejectsBlankDisplayName() {
        Set<ConstraintViolation<CreateRoomRequest>> violations =
                validator.validate(new CreateRoomRequest("", "Design jam"));

        assertThat(violations).hasSize(2); // @NotBlank + @Size(min = 2)
        assertThat(violations).allSatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("displayName"));
    }

    @Test
    void createRoomRejectsNullDisplayName() {
        Set<ConstraintViolation<CreateRoomRequest>> violations =
                validator.validate(new CreateRoomRequest(null, null));

        // @Size treats null as valid; only @NotBlank fires
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("displayName");
    }

    @Test
    void createRoomRejectsSingleCharacterDisplayName() {
        Set<ConstraintViolation<CreateRoomRequest>> violations =
                validator.validate(new CreateRoomRequest("A", null));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("displayName");
    }

    @Test
    void createRoomRejectsDisplayNameOf25Characters() {
        Set<ConstraintViolation<CreateRoomRequest>> violations =
                validator.validate(new CreateRoomRequest("A".repeat(25), null));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("displayName");
    }

    @Test
    void createRoomAcceptsBoundaryDisplayNames() {
        assertThat(validator.validate(new CreateRoomRequest("AB", null))).isEmpty();   // 2 chars
        assertThat(validator.validate(new CreateRoomRequest("A".repeat(24), null))).isEmpty(); // 24 chars
    }

    @Test
    void createRoomRoomTitleIsOptionalButCappedAt60() {
        assertThat(validator.validate(new CreateRoomRequest("Alice", null))).isEmpty();
        assertThat(validator.validate(new CreateRoomRequest("Alice", "T".repeat(60)))).isEmpty();

        Set<ConstraintViolation<CreateRoomRequest>> violations =
                validator.validate(new CreateRoomRequest("Alice", "T".repeat(61)));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("roomTitle");
    }

    // ------------------------------------------------------------------
    // JoinRoomRequest (§3 #2)
    // ------------------------------------------------------------------

    @Test
    void joinRoomRejectsBlankTooShortAndTooLongDisplayNames() {
        assertThat(validator.validate(new JoinRoomRequest(""))).hasSize(2);          // NotBlank + Size
        assertThat(validator.validate(new JoinRoomRequest("A"))).hasSize(1);         // Size(min = 2)
        assertThat(validator.validate(new JoinRoomRequest("A".repeat(25)))).hasSize(1); // Size(max = 24)
        assertThat(validator.validate(new JoinRoomRequest(null))).hasSize(1);        // NotBlank only
    }

    @Test
    void joinRoomAcceptsBoundaryDisplayNames() {
        assertThat(validator.validate(new JoinRoomRequest("Bob"))).isEmpty();
        assertThat(validator.validate(new JoinRoomRequest("B".repeat(24)))).isEmpty();
    }

    // ------------------------------------------------------------------
    // privileged-action identity payloads (§3 #5 / #10)
    // ------------------------------------------------------------------

    @Test
    void leaveRoomRequiresParticipantIdAndToken() {
        assertThat(validator.validate(new LeaveRoomRequest(null, null))).hasSize(2);
        assertThat(validator.validate(new LeaveRoomRequest("", ""))).hasSize(2);
        assertThat(validator.validate(new LeaveRoomRequest("   ", ""))).hasSize(2); // blank counts as empty

        Set<ConstraintViolation<LeaveRoomRequest>> violations =
                validator.validate(new LeaveRoomRequest("30000000-0000-0000-0000-000000000003", ""));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("participantToken");
    }

    @Test
    void canvasAuthRequiresParticipantIdAndToken() {
        assertThat(validator.validate(new CanvasAuthRequest(null, null))).hasSize(2);
        assertThat(validator.validate(new CanvasAuthRequest("", "token"))).hasSize(1);
        assertThat(validator.validate(new CanvasAuthRequest("id", ""))).hasSize(1);

        assertThat(validator.validate(new CanvasAuthRequest(
                "30000000-0000-0000-0000-000000000003",
                "50000000-0000-0000-0000-000000000005"))).isEmpty();
    }
}
