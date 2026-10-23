package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.exception.ResourceConflictException;
import com.kryukow.HotelReservationService.mapper.UserMapper;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.UserRepository;
import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher domainEventPublisher;


    @Transactional
    public UserResponseDto update(Long id, UserCreateUpdateRequestDto request) {
        validatePasswordLength(request.getPassword());
        log.info("Обновление пользователя с ID: {}", id);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));

        if (!user.getEmail().equals(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new ResourceConflictException("Email '" + request.getEmail() + "' уже используется другим пользователем");
        }

        if (!user.getName().equals(request.getName()) && userRepository.existsByName(request.getName())) {
            throw new ResourceConflictException("Имя '" + request.getName() + "' уже используется другим пользователем");
        }

        user.setEmail(request.getEmail());
        user.setName(request.getName());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        User updated = userRepository.save(user);
        log.info("Пользователь обновлён: {}", id);

        return userMapper.toResponseDto(updated);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> getAll(int page, int size) {
        log.info("Получить всех пользователей: page={}, size={}", page, size);

        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be less than zero");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage = userRepository.findAll(pageable);

        var userDtos = userPage.getContent()
                .stream()
                .map(userMapper::toResponseDto)
                .toList();

        return new PageResponseDto<>(userDtos, userPage);
    }


    @Transactional(readOnly = true)
    public UserResponseDto getById(Long id) {
        log.info("Получить пользователя по ID: {}", id);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));

        return userMapper.toResponseDto(user);
    }



    @Transactional
    public void delete(Long id) {
        log.info("Удаление пользователя с ID: {}", id);

        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException("User", id);
        }

        userRepository.deleteById(id);

        log.info("Пользователь удалён: {}", id);
    }

    @Transactional
    public void changeRole(Long id, Role newRole) {
        log.info("Изменение роли пользователя {} на {}", id, newRole);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));

        user.setRole(newRole);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> getAllByName(String name, int page, int size) {
        log.info("Поиск пользователей по имени: {}, page={}, size={}", name, page, size);

        // 🔒 Валидация
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be less than zero");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage = userRepository.findAllByName(name, pageable);

        var userDtos = userPage.getContent()
                .stream()
                .map(userMapper::toResponseDto)
                .toList();

        return new PageResponseDto<>(userDtos, userPage);
    }

    @Transactional
    public UserResponseDto create(UserCreateUpdateRequestDto request) {
        validatePasswordLength(request.getPassword());
        log.info("Регистрация нового пользователя");

        if (userRepository.existsByName(request.getName())) {
            throw new ResourceConflictException("Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResourceConflictException("Email already exists");
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);

        User saved = userRepository.save(user);
        log.info("Пользователь зарегистрирован с ID: {}", saved.getId());
        domainEventPublisher.publishUserRegistered(saved.getId());

        return userMapper.toResponseDto(saved);
    }


    private void validatePasswordLength(String password) {
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes");
        }
    }
}
