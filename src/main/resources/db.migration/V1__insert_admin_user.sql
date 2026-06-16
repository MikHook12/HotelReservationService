INSERT INTO users (name, password, email, role)
SELECT 'admin', '$2a$10$NkM1Qr8fE5xR3zF9gYqJXeXxXxXxXxXxXxXx', 'admin@example.com', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE name = 'admin');
