-- Database provisioner requires utf8mb4_bin and InnoDB; connections explicitly use UTC.
CREATE INDEX projects_order ON projects(created_at,id);
CREATE INDEX users_order ON users(created_at,id);
CREATE INDEX clients_order ON external_clients(created_at,id);
