package com.finance.service.impl;

import com.finance.command.CreateTargetCommand;
import com.finance.command.TargetCategoryCommand;
import com.finance.domain.ParticipationType;
import com.finance.domain.TargetType;
import com.finance.exception.InvalidTargetScopeException;
import com.finance.exception.TargetAlreadyExistsException;
import com.finance.exception.TargetNotFoundException;
import com.finance.repository.CategoryRepository;
import com.finance.repository.TargetCategoryRepository;
import com.finance.repository.TargetRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresTargetServiceTest {

    @Mock TargetRepository targetRepository;
    @Mock TargetCategoryRepository targetCategoryRepository;
    @Mock CategoryRepository categoryRepository;

    // EntityManager is @PersistenceContext — null in unit tests.
    // None of these tests reach getTargetStatus or computeSpent which use it.
    @InjectMocks PostgresTargetService service;

    private static final UUID USER_ID = UUID.randomUUID();

    @Nested
    class ValidateScope {

        @Test
        void categoryTargetWithNoCategories_throwsInvalidTargetScopeException() {
            CreateTargetCommand cmd = new CreateTargetCommand(
                    TargetType.CATEGORY, new BigDecimal("200.00"), 2026, 1, List.of());

            assertThatThrownBy(() -> service.createTarget(USER_ID, cmd))
                    .isInstanceOf(InvalidTargetScopeException.class);
        }

        @Test
        void categoryTargetWithTwoInclusiveCategories_throwsInvalidTargetScopeException() {
            List<TargetCategoryCommand> categories = List.of(
                    new TargetCategoryCommand(UUID.randomUUID(), ParticipationType.INCLUSIVE),
                    new TargetCategoryCommand(UUID.randomUUID(), ParticipationType.INCLUSIVE));
            CreateTargetCommand cmd = new CreateTargetCommand(
                    TargetType.CATEGORY, new BigDecimal("200.00"), 2026, 1, categories);

            assertThatThrownBy(() -> service.createTarget(USER_ID, cmd))
                    .isInstanceOf(InvalidTargetScopeException.class);
        }

        @Test
        void multiCategoryTargetWithOneInclusiveCategory_throwsInvalidTargetScopeException() {
            List<TargetCategoryCommand> categories = List.of(
                    new TargetCategoryCommand(UUID.randomUUID(), ParticipationType.INCLUSIVE));
            CreateTargetCommand cmd = new CreateTargetCommand(
                    TargetType.MULTI_CATEGORY, new BigDecimal("500.00"), 2026, 1, categories);

            assertThatThrownBy(() -> service.createTarget(USER_ID, cmd))
                    .isInstanceOf(InvalidTargetScopeException.class);
        }
    }

    @Nested
    class CreateTarget {

        @Test
        void duplicateTarget_throwsTargetAlreadyExistsException() {
            // TOTAL with empty categories passes validation, hits the duplicate check
            CreateTargetCommand cmd = new CreateTargetCommand(
                    TargetType.TOTAL, new BigDecimal("1000.00"), 2026, 1, List.of());

            when(targetRepository.existsByUserIdAndPeriodYearAndPeriodMonthAndTargetTypeAndDeletedAtIsNull(
                    USER_ID, 2026, 1, "TOTAL"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.createTarget(USER_ID, cmd))
                    .isInstanceOf(TargetAlreadyExistsException.class);
        }
    }

    @Nested
    class DeleteTarget {

        @Test
        void targetNotFound_throwsTargetNotFoundException() {
            UUID targetId = UUID.randomUUID();

            when(targetRepository.findActiveById(targetId, USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteTarget(USER_ID, targetId))
                    .isInstanceOf(TargetNotFoundException.class);
        }
    }
}
