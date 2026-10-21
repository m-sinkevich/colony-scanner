-- ================================================================
--  Сканер для ролевой игры — таблицы в Supabase
--  Выполнить один раз: Supabase → SQL Editor → вставить → Run
-- ================================================================

-- Типы сканеров (профили игроков). id — короткий латинский код.
create table if not exists public.scan_modes (
  id        text primary key,           -- medic, tech, sci …
  name      text not null,              -- «Медицинский»
  color     text default '#3DFFB0',     -- цвет интерфейса
  subtitle  text default '',            -- подпись под названием
  sort      int  default 0,             -- порядок в списке
  active    boolean default true
);

-- Записи меток: что показать при сканировании кода метки данным сканером.
create table if not exists public.scan_tags (
  id         bigint generated always as identity primary key,
  code       text not null,             -- код метки: UID (04A2B3C4…) или текст, записанный на метку
  scanner    text not null default '*', -- id из scan_modes, или * — для любого сканера
  title      text default '',           -- заголовок результата
  body       text default '',           -- текст результата (переносы строк сохраняются)
  image_url  text,                      -- полная ссылка или имя файла в бакете scan-images
  active     boolean default true,
  note       text,                      -- заметка для мастеров, в приложение не попадает
  updated_at timestamptz default now(),
  unique (code, scanner)
);

-- Доступ по публичному ключу — как у остальных таблиц «Терминала колониста»:
-- чтение нужно приложению, запись — панели мастера (scanner_admin.html).
alter table public.scan_modes enable row level security;
alter table public.scan_tags  enable row level security;

drop policy if exists "scan_modes read" on public.scan_modes;
drop policy if exists "scan_modes all"  on public.scan_modes;
create policy "scan_modes all" on public.scan_modes for all to anon, authenticated using (true) with check (true);

drop policy if exists "scan_tags read" on public.scan_tags;
drop policy if exists "scan_tags all"  on public.scan_tags;
create policy "scan_tags all" on public.scan_tags for all to anon, authenticated using (true) with check (true);

-- Картинки хранятся в уже существующем публичном бакете content-media, в папке scanner/.
-- Если бакета ещё нет — создаём (политики загрузки для anon уже описаны в README «Терминала колониста»).
insert into storage.buckets (id, name, public)
values ('content-media', 'content-media', true)
on conflict (id) do update set public = true;

-- Профили по умолчанию (поправьте под свою игру)
insert into public.scan_modes (id, name, color, subtitle, sort) values
  ('medic', 'Медицинский', '#3DFFB0', 'Биомонитор полевого медика', 1),
  ('tech',  'Технический', '#3DB8FF', 'Диагностика оборудования и конструкций', 2),
  ('sci',   'Научный',     '#FFB84D', 'Спектральный и биологический анализ', 3)
on conflict (id) do nothing;

-- Пример записей
insert into public.scan_tags (code, scanner, title, body, image_url) values
  ('DEMO', 'medic', 'Пациент: стабилен', E'Пульс: 72 уд/мин\nТемпература: 36.8 °C', null),
  ('DEMO', 'tech',  'Генератор Г-12',    E'Состояние: исправен\nЗаряд: 64%',           null)
on conflict (code, scanner) do nothing;

-- ================================================================
--  Настройки сканеров для всех телефонов (задаются в терминале мастера:
--  «Метки NFC» → «Настройки сканеров»). Одна строка с id = 1.
--  Пустое значение (null) — настройка не задана, на телефоне остаётся своя.
--  Телефоны получают настройки при обновлении базы.
-- ================================================================
create table if not exists public.scan_settings (
  id              int primary key default 1 check (id = 1),
  session_seconds int check (session_seconds between 0 and 3600), -- длительность сессии сканирования, 0 — без ограничения
  auto_scan       boolean,   -- реагировать на метку без нажатия «Сканировать»
  all_tag_types   boolean,   -- опрашивать все типы NFC (медленнее)
  master_pin      text,      -- PIN служебного меню (минимум 4 цифры)
  updated_at      timestamptz default now()
);
alter table public.scan_settings enable row level security;
drop policy if exists "scan_settings all" on public.scan_settings;
create policy "scan_settings all" on public.scan_settings for all to anon, authenticated using (true) with check (true);
insert into public.scan_settings (id) values (1) on conflict (id) do nothing;
