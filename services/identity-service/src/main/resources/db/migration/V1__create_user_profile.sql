CREATE TABLE user_profile (
    id UUID PRIMARY KEY,
    keycloak_subject VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(320),
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_profile_role (
    profile_id UUID NOT NULL REFERENCES user_profile (id),
    role_name VARCHAR(32) NOT NULL,
    PRIMARY KEY (profile_id, role_name)
);
