CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email        citext UNIQUE NOT NULL,
    handle       citext UNIQUE NOT NULL,
    display_name text NOT NULL DEFAULT '',
    bio          text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_credentials (
    user_id       uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash text NOT NULL
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE recipes (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name             text NOT NULL,
    description      text NOT NULL DEFAULT '',
    tags             text[] NOT NULL DEFAULT '{}',
    parent_recipe_id uuid REFERENCES recipes(id) ON DELETE SET NULL,
    visibility       text NOT NULL DEFAULT 'private'
                     CHECK (visibility IN ('private', 'unlisted', 'public')),
    head_version     int NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_recipes_owner ON recipes(owner_id);
CREATE INDEX idx_recipes_tags ON recipes USING gin(tags);

CREATE TABLE recipe_versions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id  uuid NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    version    int NOT NULL,
    graph      jsonb NOT NULL,
    changelog  text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (recipe_id, version)
);
