package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.mapper.UserMapper;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @InjectMocks
    private UserService userService;

    private User createUser(Long id, String name, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setPassword("encodedPassword");
        user.setRole(role);
        return user;
    }

    private UserCreateUpdateRequestDto createRequest(String name, String email, String password) {
        UserCreateUpdateRequestDto request = new UserCreateUpdateRequestDto();
        request.setName(name);
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private UserResponseDto createResponseDto(Long id, String name, String email, Role role) {
        UserResponseDto dto = new UserResponseDto();
        dto.setId(id);
        dto.setName(name);
        dto.setEmail(email);
        dto.setRole(role);
        return dto;
    }

    @Test
    void createRejectsPasswordOverBcryptByteLimitBeforeCallingEncoder() {
        UserCreateUpdateRequestDto request = createRequest("john", "john@test.com", "я".repeat(37));
        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 UTF-8 bytes");
        verifyNoInteractions(passwordEncoder, userRepository, userMapper, domainEventPublisher);
    }

    @Test
    void updateRejectsPasswordOverBcryptByteLimitBeforeCallingEncoder() {
        UserCreateUpdateRequestDto request = createRequest("john", "john@test.com", "я".repeat(37));
        assertThatThrownBy(() -> userService.update(1L, request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 UTF-8 bytes");
        verifyNoInteractions(passwordEncoder, userRepository, userMapper, domainEventPublisher);
    }

    // ========== CREATE ==========

    @Test
    void create_ShouldSaveAndReturnUser() {
        // given
        UserCreateUpdateRequestDto request = createRequest("john", "john@test.com", "password123");
        User user = createUser(null, "john", "john@test.com", Role.USER);
        User savedUser = createUser(1L, "john", "john@test.com", Role.USER);
        UserResponseDto responseDto = createResponseDto(1L, "john", "john@test.com", Role.USER);

        when(userRepository.existsByName(request.getName())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userMapper.toEntity(request)).thenReturn(user);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(user)).thenReturn(savedUser);
        when(userMapper.toResponseDto(savedUser)).thenReturn(responseDto);

        // when
        UserResponseDto result = userService.create(request);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("john");
        assertThat(result.getRole()).isEqualTo(Role.USER);
        verify(userRepository).save(user);
        assertThat(user.getPassword()).isEqualTo("hashed-password");
        verify(domainEventPublisher).publishUserRegistered(1L);
    }

    @Test
    void create_WhenNameExists_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request = createRequest("existing", "new@test.com", "password");
        when(userRepository.existsByName("existing")).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Username already exists");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void create_WhenEmailExists_ShouldThrowException() {
        // given
        UserCreateUpdateRequestDto request = createRequest("newuser", "existing@test.com", "password");
        when(userRepository.existsByName(request.getName())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Email already exists");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(domainEventPublisher);
    }

    // ========== GET BY ID ==========

    @Test
    void getById_WhenUserExists_ShouldReturnUser() {
        // given
        Long id = 1L;
        User user = createUser(id, "john", "john@test.com", Role.USER);
        UserResponseDto responseDto = createResponseDto(id, "john", "john@test.com", Role.USER);

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        // when
        UserResponseDto result = userService.getById(id);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getName()).isEqualTo("john");
    }

    @Test
    void getById_WhenUserNotFound_ShouldThrowException() {
        // given
        Long id = 999L;
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== GET ALL ==========

    @Test
    void getAll_ShouldReturnPage() {
        // given
        int page = 0;
        int size = 10;
        Pageable pageable = PageRequest.of(page, size);

        User user1 = createUser(1L, "john", "john@test.com", Role.USER);
        User user2 = createUser(2L, "jane", "jane@test.com", Role.ADMIN);
        Page<User> userPage = new PageImpl<>(List.of(user1, user2), pageable, 2);

        UserResponseDto dto1 = createResponseDto(1L, "john", "john@test.com", Role.USER);
        UserResponseDto dto2 = createResponseDto(2L, "jane", "jane@test.com", Role.ADMIN);

        when(userRepository.findAll(pageable)).thenReturn(userPage);
        when(userMapper.toResponseDto(user1)).thenReturn(dto1);
        when(userMapper.toResponseDto(user2)).thenReturn(dto2);

        // when
        PageResponseDto<UserResponseDto> result = userService.getAll(page, size);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    void getAll_ShouldThrowException_WhenPageInvalid() {
        assertThatThrownBy(() -> userService.getAll(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index must not be less than zero");
    }

    @Test
    void getAll_ShouldThrowException_WhenSizeInvalid() {
        assertThatThrownBy(() -> userService.getAll(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        assertThatThrownBy(() -> userService.getAll(0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be between 1 and 100");
    }

    // ========== UPDATE ==========

    @Test
    void update_ShouldUpdateUser() {
        // given
        Long id = 1L;
        UserCreateUpdateRequestDto request = createRequest("updatedName", "updated@test.com", "newPassword");
        User existingUser = createUser(id, "oldName", "old@test.com", Role.USER);
        User updatedUser = createUser(id, "updatedName", "updated@test.com", Role.USER);
        UserResponseDto responseDto = createResponseDto(id, "updatedName", "updated@test.com", Role.USER);

        when(userRepository.findById(id)).thenReturn(Optional.of(existingUser));
        when(userRepository.existsByEmail("updated@test.com")).thenReturn(false);
        when(userRepository.existsByName("updatedName")).thenReturn(false);
        when(userRepository.save(existingUser)).thenReturn(updatedUser);
        when(passwordEncoder.encode("newPassword")).thenReturn("updated-hash");
        when(userMapper.toResponseDto(updatedUser)).thenReturn(responseDto);

        // when
        UserResponseDto result = userService.update(id, request);

        // then
        assertThat(result.getName()).isEqualTo("updatedName");
        assertThat(result.getEmail()).isEqualTo("updated@test.com");
        assertThat(existingUser.getName()).isEqualTo("updatedName");
        assertThat(existingUser.getEmail()).isEqualTo("updated@test.com");
        assertThat(existingUser.getPassword()).isEqualTo("updated-hash");
    }

    @Test
    void update_WhenUserNotFound_ShouldThrowException() {
        // given
        Long id = 999L;
        UserCreateUpdateRequestDto request = createRequest("name", "email@test.com", "pass");

        when(userRepository.findById(id)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.update(id, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    @Test
    void update_WhenEmailExistsForAnotherUser_ShouldThrowException() {
        // given
        Long id = 1L;
        UserCreateUpdateRequestDto request = createRequest("name", "existing@test.com", "pass");
        User existingUser = createUser(id, "oldName", "old@test.com", Role.USER);

        when(userRepository.findById(id)).thenReturn(Optional.of(existingUser));
        when(userRepository.existsByEmail("existing@test.com")).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.update(id, request))
                .isInstanceOf(com.kryukow.HotelReservationService.exception.ResourceConflictException.class)
                .hasMessageContaining("Email 'existing@test.com' уже используется");
    }

    // ========== DELETE ==========

    @Test
    void delete_WhenUserExists_ShouldDelete() {
        // given
        Long id = 1L;
        when(userRepository.existsById(id)).thenReturn(true);
        doNothing().when(userRepository).deleteById(id);

        // when
        userService.delete(id);

        // then
        verify(userRepository).deleteById(id);
    }

    @Test
    void delete_WhenUserNotFound_ShouldThrowException() {
        // given
        Long id = 999L;
        when(userRepository.existsById(id)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> userService.delete(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== CHANGE ROLE ==========

    @Test
    void changeRole_WhenUserExists_ShouldChangeRole() {
        // given
        Long id = 1L;
        Role newRole = Role.ADMIN;
        User user = createUser(id, "john", "john@test.com", Role.USER);

        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        // when
        userService.changeRole(id, newRole);

        // then
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        verify(userRepository).findById(id);
    }

    @Test
    void changeRole_WhenUserNotFound_ShouldThrowException() {
        // given
        Long id = 999L;
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.changeRole(id, Role.ADMIN))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }

    // ========== GET BY NAME ==========

    @Test
    void getAllByName_ShouldReturnPage() {
        // given
        String name = "john";
        int page = 0;
        int size = 10;
        Pageable pageable = PageRequest.of(page, size);

        User user = createUser(1L, "john", "john@test.com", Role.USER);
        Page<User> userPage = new PageImpl<>(List.of(user), pageable, 1);
        UserResponseDto responseDto = createResponseDto(1L, "john", "john@test.com", Role.USER);

        when(userRepository.findAllByName(name, pageable)).thenReturn(userPage);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        // when
        PageResponseDto<UserResponseDto> result = userService.getAllByName(name, page, size);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("john");
    }

    @Test
    void getAllByName_ShouldThrowException_WhenPageInvalid() {
        assertThatThrownBy(() -> userService.getAllByName("john", -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index must not be less than zero");
    }

    @Test
    void getAllByName_ShouldThrowException_WhenSizeInvalid() {
        assertThatThrownBy(() -> userService.getAllByName("john", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be between 1 and 100");
    }
}