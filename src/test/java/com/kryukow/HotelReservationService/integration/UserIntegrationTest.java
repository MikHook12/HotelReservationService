package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    private UserCreateUpdateRequestDto createValidRequest() {
        UserCreateUpdateRequestDto request = new UserCreateUpdateRequestDto();
        request.setName("testuser");
        request.setEmail("test@example.com");
        request.setPassword("password123");
        return request;
    }

    // ========== CREATE ==========

    @Test
    void create_ShouldSaveAndReturnUser() {
        // given
        UserCreateUpdateRequestDto request = createValidRequest();

        // when
        UserResponseDto created = userService.create(request);

        // then
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("testuser");
        assertThat(created.getEmail()).isEqualTo("test@example.com");
        assertThat(created.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void create_WhenNameExists_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("testuser");
        request2.setEmail("different@example.com");

        // when & then
        assertThatThrownBy(() -> userService.create(request2))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    void create_WhenEmailExists_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("differentuser");
        request2.setEmail("test@example.com");

        // when & then
        assertThatThrownBy(() -> userService.create(request2))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Email already exists");
    }

    // ========== GET BY ID ==========

    @Test
    void getById_WhenUserExists_ShouldReturnUser() {
        // given
        UserCreateUpdateRequestDto request = createValidRequest();
        UserResponseDto created = userService.create(request);

        // when
        UserResponseDto found = userService.getById(created.getId());

        // then
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getName()).isEqualTo("testuser");
        assertThat(found.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getById_WhenUserNotFound_ShouldThrowException() {
        assertThatThrownBy(() -> userService.getById(999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== GET ALL ==========

    @Test
    void getAll_ShouldReturnPage() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        request1.setName("user1");
        request1.setEmail("user1@test.com");
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("user2");
        request2.setEmail("user2@test.com");
        userService.create(request2);

        // when
        PageResponseDto<UserResponseDto> result = userService.getAll(0, 10);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    void getAll_ShouldReturnEmptyPage() {
        // when
        PageResponseDto<UserResponseDto> result = userService.getAll(10, 10);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    // ========== UPDATE ==========

    @Test
    void update_ShouldUpdateUser() {
        // given
        UserCreateUpdateRequestDto request = createValidRequest();
        UserResponseDto created = userService.create(request);

        UserCreateUpdateRequestDto updateRequest = new UserCreateUpdateRequestDto();
        updateRequest.setName("updatedname");
        updateRequest.setEmail("updated@example.com");
        updateRequest.setPassword("newpassword");

        // when
        UserResponseDto updated = userService.update(created.getId(), updateRequest);

        // then
        assertThat(updated.getName()).isEqualTo("updatedname");
        assertThat(updated.getEmail()).isEqualTo("updated@example.com");
    }

    @Test
    void update_WhenUserNotFound_ShouldThrowException() {
        UserCreateUpdateRequestDto request = createValidRequest();
        assertThatThrownBy(() -> userService.update(999L, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    @Test
    void update_WhenEmailExistsForAnotherUser_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        request1.setName("user1");
        request1.setEmail("user1@test.com");
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("user2");
        request2.setEmail("user2@test.com");
        UserResponseDto user2 = userService.create(request2);

        // when & then - пытаемся обновить user2 на email user1
        UserCreateUpdateRequestDto updateRequest = createValidRequest();
        updateRequest.setName("user2_updated");
        updateRequest.setEmail("user1@test.com");

        assertThatThrownBy(() -> userService.update(user2.getId(), updateRequest))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Email 'user1@test.com' уже используется");
    }

    @Test
    void update_WhenNameExistsForAnotherUser_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        request1.setName("name1");
        request1.setEmail("email1@test.com");
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("name2");
        request2.setEmail("email2@test.com");
        UserResponseDto user2 = userService.create(request2);

        // when & then - пытаемся обновить user2 на имя user1
        UserCreateUpdateRequestDto updateRequest = createValidRequest();
        updateRequest.setName("name1");
        updateRequest.setEmail("email2@test.com");

        assertThatThrownBy(() -> userService.update(user2.getId(), updateRequest))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Имя 'name1' уже используется");
    }

    // ========== DELETE ==========

    @Test
    void delete_WhenUserExists_ShouldDelete() {
        // given
        UserCreateUpdateRequestDto request = createValidRequest();
        UserResponseDto created = userService.create(request);

        // when
        userService.delete(created.getId());

        // then
        assertThatThrownBy(() -> userService.getById(created.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_WhenUserNotFound_ShouldThrowException() {
        assertThatThrownBy(() -> userService.delete(999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== CHANGE ROLE ==========

    @Test
    void changeRole_ShouldChangeUserRole() {
        // given
        UserCreateUpdateRequestDto request = createValidRequest();
        UserResponseDto created = userService.create(request);
        assertThat(created.getRole()).isEqualTo(Role.USER);

        // when
        userService.changeRole(created.getId(), Role.ADMIN);

        // then
        UserResponseDto updated = userService.getById(created.getId());
        assertThat(updated.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void changeRole_WhenUserNotFound_ShouldThrowException() {
        assertThatThrownBy(() -> userService.changeRole(999L, Role.ADMIN))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== GET BY NAME ==========

    @Test
    void getAllByName_ShouldReturnUsersWithMatchingName() {
        // given
        UserCreateUpdateRequestDto request1 = createValidRequest();
        request1.setName("john");
        request1.setEmail("john1@test.com");
        userService.create(request1);

        UserCreateUpdateRequestDto request2 = createValidRequest();
        request2.setName("john2");
        request2.setEmail("john2@test.com");
        userService.create(request2);

        UserCreateUpdateRequestDto request3 = createValidRequest();
        request3.setName("jane");
        request3.setEmail("jane@test.com");
        userService.create(request3);

        // when
        PageResponseDto<UserResponseDto> result = userService.getAllByName("john", 0, 10);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent()).allMatch(user -> user.getName().equals("john"));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getAllByName_WhenNoMatch_ShouldReturnEmptyPage() {
        // when
        PageResponseDto<UserResponseDto> result = userService.getAllByName("nonexistent", 0, 10);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }
}