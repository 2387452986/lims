-- Generated from docs/reference-manifests/showcase_catalog-data-dictionaries.json.
-- The source capture contains configuration master data only; no user or historical business records.
CREATE SEQUENCE data_dictionary_code_seq START WITH 100000;

CREATE TABLE data_dictionary_series (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  code varchar(80) NOT NULL,
  parent_id uuid REFERENCES data_dictionary_series(id),
  sort_order integer NOT NULL CHECK (sort_order >= 0),
  source_system varchar(40),
  source_record_id varchar(120),
  source_path varchar(500),
  source_captured_at timestamptz,
  content_sha256 char(64),
  created_by uuid REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX data_dictionary_code_ci_ux
  ON data_dictionary_series(organization_id, upper(code));
CREATE UNIQUE INDEX data_dictionary_source_ux
  ON data_dictionary_series(organization_id, source_system, source_record_id)
  WHERE source_system IS NOT NULL AND source_record_id IS NOT NULL;
CREATE INDEX data_dictionary_tree_ix
  ON data_dictionary_series(organization_id, parent_id, sort_order, upper(code), id);

CREATE TABLE data_dictionary_revision (
  id uuid PRIMARY KEY,
  series_id uuid NOT NULL REFERENCES data_dictionary_series(id),
  predecessor_id uuid REFERENCES data_dictionary_revision(id),
  revision_number integer NOT NULL CHECK (revision_number > 0),
  name varchar(240) NOT NULL CHECK (length(btrim(name)) > 0),
  allows_children boolean NOT NULL DEFAULT false,
  state varchar(16) NOT NULL CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
  lock_version bigint NOT NULL DEFAULT 0,
  created_by uuid REFERENCES app_user(id),
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (series_id, revision_number)
);
CREATE UNIQUE INDEX data_dictionary_one_draft_ix
  ON data_dictionary_revision(series_id) WHERE state='DRAFT';
CREATE UNIQUE INDEX data_dictionary_one_published_ix
  ON data_dictionary_revision(series_id) WHERE state='PUBLISHED';
CREATE INDEX data_dictionary_revision_state_ix
  ON data_dictionary_revision(series_id, state, revision_number DESC);

CREATE TABLE data_dictionary_value (
  id uuid PRIMARY KEY,
  revision_id uuid NOT NULL REFERENCES data_dictionary_revision(id) ON DELETE CASCADE,
  source_record_id varchar(160),
  sort_order integer NOT NULL CHECK (sort_order >= 0),
  value_text varchar(1000) NOT NULL CHECK (length(btrim(value_text)) > 0),
  remark varchar(2000) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX data_dictionary_value_source_ux
  ON data_dictionary_value(revision_id, source_record_id)
  WHERE source_record_id IS NOT NULL;
CREATE INDEX data_dictionary_value_order_ix
  ON data_dictionary_value(revision_id, sort_order, id);

CREATE TEMP TABLE reference_dictionary_node (
  code varchar(80) PRIMARY KEY,
  name varchar(240) NOT NULL,
  parent_code varchar(80),
  allows_children boolean NOT NULL,
  sort_order integer NOT NULL
) ON COMMIT DROP;

INSERT INTO reference_dictionary_node(code,name,parent_code,allows_children,sort_order) VALUES
('98000', convert_from(decode('5qC35ZOB5o+P6L+w','base64'),'UTF8'), NULL, false, 0),
('95000', convert_from(decode('57O757uf5pWw5o2u5a2X5YW46aG5','base64'),'UTF8'), NULL, true, 1),
('95104', convert_from(decode('5LiT5Lia57G75YirKOS4k+WutueuoeeQhik=','base64'),'UTF8'), '95000', false, 2),
('95103', convert_from(decode('6IGM56ewKOS4k+WutueuoeeQhik=','base64'),'UTF8'), '95000', false, 3),
('95102', convert_from(decode('6IGM56ew5LiT5LiaKOS4k+WutueuoeeQhik=','base64'),'UTF8'), '95000', false, 4),
('95101', convert_from(decode('6IGM5YqhKOS4k+WutueuoeeQhik=','base64'),'UTF8'), '95000', false, 5),
('90000', convert_from(decode('6K+V5Lu25bC65a+444CB5Z6L5Y+3','base64'),'UTF8'), NULL, true, 6),
('91000', convert_from(decode('5oqX56Kz5YyW6K+V5Lu2','base64'),'UTF8'), '90000', false, 7),
('90900', convert_from(decode('5bKp55+z6K+V5Lu2','base64'),'UTF8'), '90000', false, 8),
('90800', convert_from(decode('6ZKi562L55u05b6E','base64'),'UTF8'), '90000', false, 9),
('90700', convert_from(decode('56CC5rWG6KeE5qC8','base64'),'UTF8'), '90000', false, 10),
('90600', convert_from(decode('55+z5YWs56ew57KS57qn','base64'),'UTF8'), '90000', false, 11),
('90500', convert_from(decode('56CC6KeE5qC8','base64'),'UTF8'), '90000', false, 12),
('90400', convert_from(decode('5oqX5Ya76K+V5Lu2','base64'),'UTF8'), '90000', false, 13),
('90300', convert_from(decode('5oqX5riX6K+V5Lu2','base64'),'UTF8'), '90000', false, 14),
('90200', convert_from(decode('5oqX5oqY6K+V5Lu2','base64'),'UTF8'), '90000', false, 15),
('90100', convert_from(decode('5oqX5Y6L6K+V5Lu2','base64'),'UTF8'), '90000', false, 16),
('43000', convert_from(decode('6ZKi562L54mM5Y+3','base64'),'UTF8'), '90000', false, 17),
('12000', convert_from(decode('5bKp55+z56eN57G7','base64'),'UTF8'), '90000', false, 18),
('81000', convert_from(decode('6IGM5Yqh','base64'),'UTF8'), NULL, false, 19),
('80000', convert_from(decode('5YW25LuW','base64'),'UTF8'), NULL, true, 20),
('80001', convert_from(decode('5piv5ZCm5rO16YCB','base64'),'UTF8'), '80000', false, 21),
('70000', convert_from(decode('5ZOB56eN6KeE5qC8562J57qn','base64'),'UTF8'), NULL, true, 22),
('72000', convert_from(decode('5rC05rqQ56eN57G7','base64'),'UTF8'), '70000', false, 23),
('71600', convert_from(decode('5Zyf5bel5qC85qCF6KeE5qC8','base64'),'UTF8'), '70000', false, 24),
('71500', convert_from(decode('5Zyf5aOk56eN57G7','base64'),'UTF8'), '70000', false, 25),
('71400', convert_from(decode('5aSW5Yqg5YmC6KeE5qC8','base64'),'UTF8'), '70000', false, 26),
('71300', convert_from(decode('56CW5by65bqm562J57qn','base64'),'UTF8'), '70000', false, 27),
('71200', convert_from(decode('5Z6L6ZKi54mM5Y+3','base64'),'UTF8'), '70000', false, 28),
('71100', convert_from(decode('56CC5rWG562J57qn','base64'),'UTF8'), '70000', false, 29),
('71000', convert_from(decode('55+/5rij57KJ57qn5Yir','base64'),'UTF8'), '70000', false, 30),
('70900', convert_from(decode('57KJ54Wk54Gw6KeE5qC8562J57qn','base64'),'UTF8'), '70000', false, 31),
('70800', convert_from(decode('55+z56eN57G7','base64'),'UTF8'), '70000', false, 32),
('70700', convert_from(decode('56CC5ZOB56eN','base64'),'UTF8'), '70000', false, 33),
('70600', convert_from(decode('5rC05rOl5ZOB56eN6KeE5qC8','base64'),'UTF8'), '70000', false, 34),
('70400', convert_from(decode('5re35Yed5Zyf5oqX5Ya75by65bqm562J57qn','base64'),'UTF8'), '70000', false, 35),
('70300', convert_from(decode('5re35Yed5Zyf5oqX5riX5by65bqm562J57qn','base64'),'UTF8'), '70000', false, 36),
('70200', convert_from(decode('5re35Yed5Zyf5oqX5Y6L5by65bqm562J57qn','base64'),'UTF8'), '70000', false, 37),
('70100', convert_from(decode('5re35Yed5Zyf5oqX5oqY5by65bqm562J57qn','base64'),'UTF8'), '70000', false, 38),
('22000', convert_from(decode('5re35Yed5Zyf5oqX56Kz5YyW5oCn6IO9562J57qn','base64'),'UTF8'), '70000', false, 39),
('60000', convert_from(decode('5a6h5p+l5YaF5a65','base64'),'UTF8'), NULL, false, 40),
('53000', convert_from(decode('5qC35ZOB5ZCN56ew','base64'),'UTF8'), NULL, true, 41),
('53980', convert_from(decode('5YWs6Lev5bel56iL5peg5py657uT5ZCI5paZ56iz5a6a5p2Q5paZ55So57KJ54Wk54Gw','base64'),'UTF8'), '53000', false, 42),
('53970', convert_from(decode('5YWs6Lev5bel56iL5peg5py657uT5ZCI5paZ56iz5a6a5p2Q5paZ55So55+z54Gw','base64'),'UTF8'), '53000', false, 43),
('53960', convert_from(decode('5YWs6Lev5bel56iL5peg5py657uT5ZCI5paZ56iz5a6a5p2Q5paZ','base64'),'UTF8'), '53000', false, 44),
('53950', convert_from(decode('56CC5rWG5oqX5Ya75oCn','base64'),'UTF8'), '53000', false, 45),
('53940', convert_from(decode('5rC05bel5re35Yed5Zyf6YWN5ZCI5q+U','base64'),'UTF8'), '53000', false, 46),
('53920', convert_from(decode('57KJ54Wk54Gw','base64'),'UTF8'), '53000', false, 47),
('53910', convert_from(decode('55+z','base64'),'UTF8'), '53000', false, 48),
('53900', convert_from(decode('56CC','base64'),'UTF8'), '53000', false, 49),
('53800', convert_from(decode('5oqX5byv5by65bqm44CB5oqX5oqY5by65bqm','base64'),'UTF8'), '53000', false, 50),
('53700', convert_from(decode('5re35Yed5Zyf5oqX5Ya7','base64'),'UTF8'), '53000', false, 51),
('53600', convert_from(decode('5re35Yed5Zyf5oqX5Y6L','base64'),'UTF8'), '53000', false, 52),
('53500', convert_from(decode('5re35Yed5Zyf5oqX5riX','base64'),'UTF8'), '53000', false, 53),
('53400', convert_from(decode('5YqI6KOC5oqX5ouJ5by65bqm','base64'),'UTF8'), '53000', false, 54),
('53300', convert_from(decode('6ZKi562L5Y6f5p2Q','base64'),'UTF8'), '53000', false, 55),
('53240', convert_from(decode('5LiJ57u05Zyf5bel572R5Z6r','base64'),'UTF8'), '53000', false, 56),
('53230', convert_from(decode('6auY5YiG5a2Q6Ziy5rC05p2Q5paZ5q2i5rC05bim','base64'),'UTF8'), '53000', false, 57),
('53220', convert_from(decode('5aGR5paZ5Zyf5bel5qC85a6k','base64'),'UTF8'), '53000', false, 58),
('53210', convert_from(decode('5Zyf5bel572R','base64'),'UTF8'), '53000', false, 59),
('53200', convert_from(decode('56CC5rWG5oqX5Y6L','base64'),'UTF8'), '53000', false, 60),
('53190', convert_from(decode('5aGR5paZ5o6S5rC05p2/','base64'),'UTF8'), '53000', false, 61),
('53180', convert_from(decode('5Zyf5bel5biD','base64'),'UTF8'), '53000', false, 62),
('53170', convert_from(decode('5Zyf5bel5qC85qCF','base64'),'UTF8'), '53000', false, 63),
('53160', convert_from(decode('5Zyf5bel5qC85a6k','base64'),'UTF8'), '53000', false, 64),
('53150', convert_from(decode('5Zyf5bel6Iac','base64'),'UTF8'), '53000', false, 65),
('53140', convert_from(decode('5aSW5Yqg5YmC5ZCN56ew','base64'),'UTF8'), '53000', false, 66),
('53130', convert_from(decode('6ZKi5p2Q','base64'),'UTF8'), '53000', false, 67),
('53120', convert_from(decode('55S157q/55S157yG','base64'),'UTF8'), '53000', false, 68),
('53110', convert_from(decode('5rKl6Z2S54mp55CG5oCn','base64'),'UTF8'), '53000', false, 69),
('53100', convert_from(decode('5rC05rOl','base64'),'UTF8'), '53000', false, 70),
('38000', convert_from(decode('6ZKi562L54SK5o6l','base64'),'UTF8'), '53000', false, 71),
('36000', convert_from(decode('6ZKi562L5py65qKw6L+e5o6l5o6l5aS0','base64'),'UTF8'), '53000', false, 72),
('50000', convert_from(decode('6K+V6aqM5pa55rOV','base64'),'UTF8'), NULL, true, 73),
('50001', convert_from(decode('5rC05rOl5a6J5a6a5oCn','base64'),'UTF8'), '50000', false, 74),
('40000', convert_from(decode('6K+V6aqM5aSH5rOo6K+05piO','base64'),'UTF8'), NULL, true, 75),
('40100', convert_from(decode('5re35Yed5Zyf5oqX5Y6L5aSH5rOo','base64'),'UTF8'), '40000', false, 76),
('30000', convert_from(decode('6ZmE5Yqg6K+05piO','base64'),'UTF8'), NULL, false, 77),
('21000', convert_from(decode('5qC35ZOB6b6E5pyf6YWN572u','base64'),'UTF8'), NULL, true, 78),
('21001', convert_from(decode('5re35Yed5Zyf56Kz5YyW6b6E5pyf','base64'),'UTF8'), '21000', false, 79),
('20000', convert_from(decode('6ZKi562L55u45YWz6YWN572u','base64'),'UTF8'), NULL, true, 80),
('99000', convert_from(decode('54SK5o6l5pa55byP','base64'),'UTF8'), '20000', false, 81),
('70500', convert_from(decode('5o6l5aS0562J57qn','base64'),'UTF8'), '20000', false, 82),
('44000', convert_from(decode('6L+e5o6l5pa55byP','base64'),'UTF8'), '20000', false, 83),
('19000', convert_from(decode('5oql5ZGK5oqs5aS0','base64'),'UTF8'), NULL, false, 84),
('18000', convert_from(decode('5qC35ZOB5qCH562+5qC35ZOB5aSE55CG6YCJ6aG5','base64'),'UTF8'), NULL, false, 85),
('17000', convert_from(decode('6K+V6aqM546v5aKD','base64'),'UTF8'), NULL, false, 86),
('16000', convert_from(decode('5oql5ZGK5Lit55qE6IGU57O75pa55byP','base64'),'UTF8'), NULL, false, 87),
('15000', convert_from(decode('5oql5ZGK5Lit5Y2V5L2N6IGU57O75Zyw5Z2A','base64'),'UTF8'), NULL, false, 88),
('14000', convert_from(decode('5pWw5o2u5L+u5pS55Y6f5Zug','base64'),'UTF8'), NULL, false, 89),
('13000', convert_from(decode('5oql5ZGK5aOw5piO','base64'),'UTF8'), NULL, false, 90),
('11000', convert_from(decode('5re35Yed5Zyf5YW75oqk5p2h5Lu2','base64'),'UTF8'), NULL, false, 91);

CREATE TEMP TABLE reference_dictionary_value (
  dictionary_code varchar(80) NOT NULL,
  source_record_id varchar(160) NOT NULL,
  sort_order integer NOT NULL,
  value_text varchar(1000) NOT NULL,
  remark varchar(2000) NOT NULL
) ON COMMIT DROP;

INSERT INTO reference_dictionary_value(dictionary_code,source_record_id,sort_order,value_text,remark) VALUES
('98000', '98000:0', 1, convert_from(decode('5peg57y66KeS5bCR5qWe77yM6KGo6Z2i5bmz5pW044CB5YWJ5rSB77yM5bC65a+456ym5ZCI5qCH5YeG6KaB5rGC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('98000', '98000:1', 2, convert_from(decode('57KJ54q277yM5peg57uT5Z2X44CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('98000', '98000:2', 3, convert_from(decode('5ray5oCB77yM5peg5LiN5rq26Kej5rKJ5reA54mp44CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('91000', '91000:0', 1, convert_from(decode('MTAwKjEwMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('91000', '91000:1', 2, convert_from(decode('MTUwKjE1MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90900', '90900:0', 1, convert_from(decode('z4Y1MHgxMDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90900', '90900:1', 2, convert_from(decode('NTB4NTB4MTAw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:0', 1, convert_from(decode('Ng==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:1', 2, convert_from(decode('OA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:2', 3, convert_from(decode('MTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:3', 4, convert_from(decode('MTI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:4', 5, convert_from(decode('MTQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:5', 6, convert_from(decode('MTY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:6', 7, convert_from(decode('MTg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:7', 8, convert_from(decode('MjA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:8', 9, convert_from(decode('MjI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:9', 10, convert_from(decode('MjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:10', 11, convert_from(decode('Mjg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:11', 12, convert_from(decode('MzI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:12', 13, convert_from(decode('MzY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:13', 14, convert_from(decode('NDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90800', '90800:14', 15, convert_from(decode('NTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90700', '90700:0', 1, convert_from(decode('NzAuN3g3MC43eDcwLjc=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:0', 1, convert_from(decode('Ne+9njEw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:1', 2, convert_from(decode('Ne+9njE2','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:2', 3, convert_from(decode('Ne+9njIw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:3', 4, convert_from(decode('Ne+9njI1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:4', 5, convert_from(decode('Ne+9njMxLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:5', 6, convert_from(decode('Ne+9njQw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:6', 7, convert_from(decode('MTDvvZ4yMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:7', 8, convert_from(decode('MTbvvZ4zMS41','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:8', 9, convert_from(decode('MjDvvZ40MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:9', 10, convert_from(decode('MzEuNe+9njYz','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:10', 11, convert_from(decode('NDDvvZ44MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:11', 12, convert_from(decode('MTDvvZ4xNg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:12', 13, convert_from(decode('MTbvvZ4yNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:13', 14, convert_from(decode('MjXvvZ4zMS41','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90600', '90600:14', 15, convert_from(decode('Ne+9njE1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90500', '90500:0', 1, convert_from(decode('57uG56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90500', '90500:1', 2, convert_from(decode('5Lit56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90500', '90500:2', 3, convert_from(decode('57KX56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90500', '90500:3', 4, convert_from(decode('54m557uG56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90400', '90400:0', 1, convert_from(decode('MTAwKjEwMCo0MDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90400', '90400:1', 2, convert_from(decode('MTAwKjEwMCoyMDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90300', '90300:0', 1, convert_from(decode('MTc1KjE4NSoxNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90300', '90300:1', 2, convert_from(decode('z4YxMDDDlzE1MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90200', '90200:0', 1, convert_from(decode('NTUwKjE1MCoxNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90200', '90200:1', 2, convert_from(decode('NDUwKjE1MCoxNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90200', '90200:2', 3, convert_from(decode('NjAwKjE1MCoxNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90200', '90200:3', 4, convert_from(decode('NDAwKjEwMCoxMDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90100', '90100:0', 1, convert_from(decode('MTAwKjEwMCoxMDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90100', '90100:1', 2, convert_from(decode('MTUwKjE1MCoxNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90100', '90100:2', 3, convert_from(decode('MzAwKjMwMCozMDA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('90100', '90100:3', 4, convert_from(decode('NDUwKjQ1MCo0NTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:0', 1, convert_from(decode('SFJCMzM1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:1', 2, convert_from(decode('SFJCMzM1RQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:2', 3, convert_from(decode('SFJCNDAw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:3', 4, convert_from(decode('SFJCNDAwRQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:4', 5, convert_from(decode('SFJCNTAw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:5', 6, convert_from(decode('SFJCNTAwRQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:6', 7, convert_from(decode('SFJCRjM1MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:7', 8, convert_from(decode('SFJCRjQwMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:8', 9, convert_from(decode('SFJCRjQwMEU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:9', 10, convert_from(decode('SFJCRjUwMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:10', 11, convert_from(decode('SFJCRjUwMEU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:11', 12, convert_from(decode('SFBCMjM1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:12', 13, convert_from(decode('SFBCMzAw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('43000', '43000:13', 14, convert_from(decode('Q1JCNTUw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:0', 1, convert_from(decode('5Y2155+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:1', 2, convert_from(decode('5rKJ56ev5bKp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:2', 3, convert_from(decode('5Y+Y6LSo5bKp5oiW5rex5oiQ55qE54Gr5oiQ5bKp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:3', 4, convert_from(decode('5Za35Ye655qE54Gr5oiQ5bKp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:4', 5, convert_from(decode('5bKp5rWG5bKp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('12000', '12000:5', 6, convert_from(decode('5Y+Y6LSo5bKp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:0', 1, convert_from(decode('6JCl5Lia5ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:1', 2, convert_from(decode('5Y+45py6','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:2', 3, convert_from(decode('5qOA5rWL5ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:3', 4, convert_from(decode('5pS25qC35ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:4', 5, convert_from(decode('5qOA5rWL6LSf6LSj5Lq6','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:5', 6, convert_from(decode('5qGp5Z+65qOA5rWL6LSf6LSj5Lq6','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:6', 7, convert_from(decode('5oql5ZGK57yW6L6R5ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:7', 8, convert_from(decode('5oql5ZGK5a6h5qC45ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:8', 9, convert_from(decode('5oql5ZGK562+5Y+R5ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:9', 10, convert_from(decode('6aG555uu6LSf6LSj5Lq6','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:10', 11, convert_from(decode('5Lya6K6h','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:11', 12, convert_from(decode('5omn6KGM6JGj5LqL','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:12', 13, convert_from(decode('57uP55CG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:13', 14, convert_from(decode('5biC5Zy65oC755uR','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:14', 15, convert_from(decode('6LSi5Yqh5oC755uR','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:15', 16, convert_from(decode('5oqA5pyv5oC755uR','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:16', 17, convert_from(decode('5Ymv5oC757uP55CG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:17', 18, convert_from(decode('5oC75bel56iL5biI','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:18', 19, convert_from(decode('5biC5Zy66YOo5Ymv6YOo6ZW/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:19', 20, convert_from(decode('5biC5Zy66YOo6YOo6ZW/5Yqp55CG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:20', 21, convert_from(decode('5Yqe5YWs5a6k6IGM5ZGY','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:21', 22, convert_from(decode('5qOA5rWL6YOo6YOo6ZW/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:22', 23, convert_from(decode('57u85ZCI5Yqe6YOo6ZW/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('81000', '81000:23', 24, convert_from(decode('5Ye657qz','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('80001', '80001:0', 1, convert_from(decode('5rO16YCB','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('80001', '80001:1', 2, convert_from(decode('6Z2e5rO16YCB','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('72000', '72000:0', 1, convert_from(decode('5rGf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('72000', '72000:1', 2, convert_from(decode('5rKz','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('72000', '72000:2', 3, convert_from(decode('5rmW','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('72000', '72000:3', 4, convert_from(decode('5rW3','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('72000', '72000:4', 5, convert_from(decode('6ZK75LqV','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71600', '71600:0', 1, convert_from(decode('6IGa5LiZ54Ov5Y2V5ouJVEdERzUw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71600', '71600:1', 2, convert_from(decode('6IGa5LiZ54Ov5Y+M5ouJVEdTRzUwNTA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71600', '71600:2', 3, convert_from(decode('6auY5a+G5bqm6IGa5LmZ54Ov5Y2V5ouJVEdERzgw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71600', '71600:3', 4, convert_from(decode('546755KD57qk57u0RUdBMsOXMig4MMOXODAp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:0', 1, convert_from(decode('5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:1', 2, convert_from(decode('5a6V5rij','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:2', 3, convert_from(decode('5LqM54Gw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:3', 4, convert_from(decode('55+z5bGR','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:4', 5, convert_from(decode('5rC05rOl56iz5a6a56KO55+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:5', 6, convert_from(decode('6buE56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:6', 7, convert_from(decode('55+z57KJ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71500', '71500:7', 8, convert_from(decode('5Lit57KX56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:0', 1, convert_from(decode('6auY5oCn6IO95YeP5rC05YmCSFBXUi1B','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:1', 2, convert_from(decode('6auY5oCn6IO95YeP5rC05YmCSFBXUi1T','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:2', 3, convert_from(decode('6auY5oCn6IO95YeP5rC05YmCSFBXUi1S','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:3', 4, convert_from(decode('6auY5pWI5YeP5rC05YmCSFdSLVM=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:4', 5, convert_from(decode('6auY5pWI5YeP5rC05YmCSFdSLVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:5', 6, convert_from(decode('5pmu6YCa5YeP5rC05YmCV1ItQQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:6', 7, convert_from(decode('5pmu6YCa5YeP5rC05YmCV1ItUw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:7', 8, convert_from(decode('5pmu6YCa5YeP5rC05YmCV1ItUg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:8', 9, convert_from(decode('5byV5rCU5YeP5rC05YmCQUVXUg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:9', 10, convert_from(decode('5rO16YCB5YmCUEE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:10', 11, convert_from(decode('5pep5by65YmCQWM=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:11', 12, convert_from(decode('57yT5Yed5YmCUmU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71400', '71400:12', 13, convert_from(decode('5byV5rCU5YmCQUU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71300', '71300:0', 1, convert_from(decode('TVUxMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71300', '71300:1', 2, convert_from(decode('TVUxNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71300', '71300:2', 3, convert_from(decode('TVUyMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71300', '71300:3', 4, convert_from(decode('TVUyNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71300', '71300:4', 5, convert_from(decode('TVUzMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:0', 1, convert_from(decode('UTE5NQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:1', 2, convert_from(decode('UTIxNUE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:2', 3, convert_from(decode('UTIxNUI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:3', 4, convert_from(decode('UTIzNUE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:4', 5, convert_from(decode('UTIzNUI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:5', 6, convert_from(decode('UTIzNUM=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:6', 7, convert_from(decode('UTIzNUQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:7', 8, convert_from(decode('UTI3NUE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:8', 9, convert_from(decode('UTI3NUI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:9', 10, convert_from(decode('UTI3NUM=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71200', '71200:10', 11, convert_from(decode('UTI3NUQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:0', 1, convert_from(decode('TTIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:1', 2, convert_from(decode('TTUuMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:2', 3, convert_from(decode('TTcuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:3', 4, convert_from(decode('TTEw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:4', 5, convert_from(decode('TTE1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:5', 6, convert_from(decode('TTIw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:6', 7, convert_from(decode('TTI1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71100', '71100:7', 8, convert_from(decode('TTMw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71000', '71000:0', 1, convert_from(decode('UzEwNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71000', '71000:1', 2, convert_from(decode('Uzk1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('71000', '71000:2', 3, convert_from(decode('Uzc1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:0', 1, convert_from(decode('Q+exu+KFoOe6pw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:1', 2, convert_from(decode('Q+exu0lJ57qn','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:2', 3, convert_from(decode('Q+exu+KFoue6pw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:3', 4, convert_from(decode('Ruexu+KFoOe6pw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:4', 5, convert_from(decode('Ruexu0lJ57qn','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70900', '70900:5', 6, convert_from(decode('Ruexu+KFoue6pw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70800', '70800:0', 1, convert_from(decode('5Y2155+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70800', '70800:1', 2, convert_from(decode('56KO55+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70800', '70800:2', 3, convert_from(decode('56KO55+z77yI5Y+Y6LSo5bKp77yJ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70800', '70800:3', 4, convert_from(decode('56KO55+z77yI5bKp5rWG5bKp77yJ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70800', '70800:4', 5, convert_from(decode('56KO55+z77yI5rKJ56ev5bKp77yJ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70700', '70700:0', 1, convert_from(decode('5aSp54S256CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70700', '70700:1', 2, convert_from(decode('5py65Yi256CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70700', '70700:2', 3, convert_from(decode('5re35ZCI56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70700', '70700:3', 4, convert_from(decode('5Lq65bel56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:0', 1, convert_from(decode('UMK34oWgNDIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:1', 2, convert_from(decode('UMK34oWgNDIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:2', 3, convert_from(decode('UMK34oWgNTIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:3', 4, convert_from(decode('UMK34oWgNTIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:4', 5, convert_from(decode('UMK34oWgNjIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:5', 6, convert_from(decode('UMK34oWgNjIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:6', 7, convert_from(decode('UMK34oWhNDIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:7', 8, convert_from(decode('UMK34oWhNDIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:8', 9, convert_from(decode('UMK34oWhNTIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:9', 10, convert_from(decode('UMK34oWhNTIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:10', 11, convert_from(decode('UMK34oWhNjIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:11', 12, convert_from(decode('UMK34oWhNjIuNVI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:12', 13, convert_from(decode('UMK3TzQyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:13', 14, convert_from(decode('UMK3TzQyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:14', 15, convert_from(decode('UMK3TzUyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:15', 16, convert_from(decode('UMK3TzUyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:16', 17, convert_from(decode('UMK3U8K3QTMyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:17', 18, convert_from(decode('UMK3U8K3QTMyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:18', 19, convert_from(decode('UMK3U8K3QTQyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:19', 20, convert_from(decode('UMK3U8K3QTQyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:20', 21, convert_from(decode('UMK3U8K3QTUyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:21', 22, convert_from(decode('UMK3U8K3QTUyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:22', 23, convert_from(decode('UMK3U8K3QjMyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:23', 24, convert_from(decode('UMK3U8K3QjMyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:24', 25, convert_from(decode('UMK3U8K3QjQyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:25', 26, convert_from(decode('UMK3U8K3QjQyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:26', 27, convert_from(decode('UMK3U8K3QjUyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:27', 28, convert_from(decode('UMK3U8K3QjUyLjVS','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:28', 29, convert_from(decode('UMK3TVNSNDIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:29', 30, convert_from(decode('UMK3SFNSNDIuNQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70600', '70600:30', 31, convert_from(decode('UMK3UDMyLjU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70400', '70400:0', 1, convert_from(decode('RjUw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70400', '70400:1', 2, convert_from(decode('RjEwMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70400', '70400:2', 3, convert_from(decode('RjE1MA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70400', '70400:3', 4, convert_from(decode('RjIwMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:0', 1, convert_from(decode('UDI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:1', 2, convert_from(decode('UDQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:2', 3, convert_from(decode('UDY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:3', 4, convert_from(decode('UDg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:4', 5, convert_from(decode('UDEw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:5', 6, convert_from(decode('UDEy','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:6', 7, convert_from(decode('VzI=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:7', 8, convert_from(decode('VzQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:8', 9, convert_from(decode('VzY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:9', 10, convert_from(decode('Vzg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:10', 11, convert_from(decode('VzEw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70300', '70300:11', 12, convert_from(decode('VzEy','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:0', 1, convert_from(decode('QzEw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:1', 2, convert_from(decode('QzE1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:2', 3, convert_from(decode('QzIw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:3', 4, convert_from(decode('QzI1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:4', 5, convert_from(decode('QzMw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:5', 6, convert_from(decode('QzM1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:6', 7, convert_from(decode('QzQw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:7', 8, convert_from(decode('QzQ1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:8', 9, convert_from(decode('QzUw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:9', 10, convert_from(decode('QzU1','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70200', '70200:10', 11, convert_from(decode('QzYw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:0', 1, convert_from(decode('My4wTXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:1', 2, convert_from(decode('My41TXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:2', 3, convert_from(decode('NC4wTXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:3', 4, convert_from(decode('NC41TXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:4', 5, convert_from(decode('NS4wTXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:5', 6, convert_from(decode('NS41TXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:6', 7, convert_from(decode('Ni4wTXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:7', 8, convert_from(decode('Ni41TXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70100', '70100:8', 9, convert_from(decode('Ny4wTXBh','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('22000', '22000:0', 1, convert_from(decode('VC3ihaA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('22000', '22000:1', 2, convert_from(decode('VC1JSQ==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('22000', '22000:2', 3, convert_from(decode('VC1JSUk=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('22000', '22000:3', 4, convert_from(decode('VC1JVg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('22000', '22000:4', 5, convert_from(decode('VC3ihaQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('60000', '60000:0', 1, convert_from(decode('5Li75L2T6LWE5qC85a6h5p+l','base64'),'UTF8'), convert_from(decode('5a6h5p+l5ZCI5ZCM55u45a+55pa55piv5ZCm5YW35aSH562+6K6i5ZCI5ZCM55qE5Li75L2T6LWE5qC877yM6JCl5Lia5omn54Wn5piv5ZCm6L+H5pyf77yM5piv5ZCm6YCa6L+H5bm05a6h77yM5piv5ZCm6KKr5ZCK6ZSA562JO+azleWumuS7o+ihqOS6uuaYr+WQpuWFt+Wkh+acieaViOi1hOagvOOAgg==','base64'),'UTF8')),
('60000', '60000:1', 2, convert_from(decode('6JCl6LWE6LSo5a6h5p+l','base64'),'UTF8'), convert_from(decode('5a6h5p+l5ZCI5ZCM55u45a+55pa55piv5ZCm5YW35aSH5rOV5b6L6KGM5pS/5rOV6KeE6KeE5a6a55qE77yM5LuO5LqL54m55a6a57uP6JCl5rS75Yqo5bqU5b2T5YW35aSH55qE5rOV5a6a57uP6JCl6LWE6LSo5Y+K55u45bqU55qE6LWE6LSo562J57qn44CC','base64'),'UTF8')),
('60000', '60000:2', 3, convert_from(decode('5Luj55CG6LWE5qC85p2D6ZmQ5a6h5p+l','base64'),'UTF8'), convert_from(decode('5a6h5p+l5aeU5omY5Luj55CG5Lq65omA5oyB55qE5o6I5p2D5aeU5omY5Lmm5piv5ZCm55yf5a6e77yM5omA5o6I5p2D6ZmQ5LiO5ZCI5ZCM6KeE5a6a55qE5p2D6ZmQ5piv5ZCm5LiA6Ie077yM5Luj55CG5p2D5piv5ZCm5bey6L+H5pyf5oiW6KKr5Y+W5raI562J5YaF5a65Lg==','base64'),'UTF8')),
('60000', '60000:3', 4, convert_from(decode('6LWE5L+h54q25Ya15a6h5p+l','base64'),'UTF8'), convert_from(decode('5a6h5p+l5ZCI5ZCM55u45a+55pa5566h55CG5rC05bmz44CB57uP6JCl6IO95Yqb44CB5oiY55Wl55uu5qCH44CB5oCd5oOz5ZOB5b6344CB56S+5Lya5L+h6KqJ77yM5Lul5Y+K5LyB5Lia55uu5YmN57uP6JCl546w54q2562J5oOF5Ya144CC','base64'),'UTF8')),
('60000', '60000:4', 5, convert_from(decode('57uP6JCl6IyD5Zu05a6h5p+l','base64'),'UTF8'), convert_from(decode('5a6h5p+l5b2T5YmN5ZCI5ZCM6aG555uu5piv5ZCm5Zyo5LyB5Lia57uP6JCl6IyD5Zu05YaF','base64'),'UTF8')),
('60000', '60000:5', 6, convert_from(decode('562+57qm5Lq65bGl57qm6IO95Yqb5a6h5p+l','base64'),'UTF8'), convert_from(decode('562+57qm5Lq65bGl57qm6IO95Yqb5a6h5p+l','base64'),'UTF8')),
('60000', '60000:6', 7, convert_from(decode('5YW25LuW5a6h5p+l5YaF5a65','base64'),'UTF8'), convert_from(decode('6Zmk5LiK6L+w5LqU5Liq5pa56Z2i5LmL5aSW55qE5YW25LuW5a6h5p+l5YaF5a6544CC','base64'),'UTF8')),
('53950', '53950:0', 1, convert_from(decode('5rC05bel56CC5rWG5oqX5Ya75oCn6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53950', '53950:1', 2, convert_from(decode('6IGa5ZCI54mp5rC05rOl6Ziy5rC056CC5rWG5oqX5Ya75oCn6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53950', '53950:2', 3, convert_from(decode('6IGa5ZCI54mp5pS55oCn5rC05rOl56CC5rWG5oqX5Ya75oCn6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:1', 2, convert_from(decode('5rC05bel56K+5Y6L5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:2', 3, convert_from(decode('5rC05bel5YWo57qn6YWN5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:3', 4, convert_from(decode('5rC05bel5aGR5oCn5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:4', 5, convert_from(decode('5pmu6YCa5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:5', 6, convert_from(decode('5YWs6Lev5bel56iL55So5rC05rOl5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53940', '53940:6', 7, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53920', '53920:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf5o6655So57KJ54Wk54Gw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53920', '53920:1', 2, convert_from(decode('55So5LqO5rC05rOl5ZKM5re35Yed5Zyf5Lit55qE57KJ54Wk54Gw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53920', '53920:2', 3, convert_from(decode('5YWs6Lev5bel56iL5peg5py657uT5ZCI5paZ56iz5a6a5p2Q5paZ55So57KJ54Wk54Gw','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf55So57KX6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:1', 2, convert_from(decode('5rC06L+Q5bel56iL55So57KX6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:2', 3, convert_from(decode('5re35Yed5Zyf55So5YaN55Sf57KX6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:3', 4, convert_from(decode('5YWs6Lev5bel56iL55So57KX6ZuG5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:4', 5, convert_from(decode('5pmu6YCa5re35Yed5Zyf55So55+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:5', 6, convert_from(decode('5bu66K6+55So5Y2155+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53910', '53910:6', 7, convert_from(decode('56KO55+z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf55So57uG6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:1', 2, convert_from(decode('5pmu6YCa5re35Yed5Zyf55So56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:2', 3, convert_from(decode('5re35Yed5Zyf5ZKM56CC5rWG55So5YaN55Sf57uG6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:3', 4, convert_from(decode('5bu66K6+55So56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:4', 5, convert_from(decode('5rC06L+Q5bel56iL55So57uG6aqo5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:5', 6, convert_from(decode('5YWs6Lev5bel56iL55So57uG6ZuG5paZ','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:6', 7, convert_from(decode('5Lq65bel56CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53900', '53900:7', 8, convert_from(decode('5aSp54S256CC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53800', '53800:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf5oqX5byv6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53800', '53800:1', 2, convert_from(decode('5pmu6YCa5re35Yed5Zyf5oqX5oqY6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53800', '53800:2', 3, convert_from(decode('5YWs6Lev5bel56iL55So5rC05rOl5re35Yed5Zyf5byv5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53800', '53800:3', 4, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf5oqX5oqY6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:0', 1, convert_from(decode('5rC05bel5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:1', 2, convert_from(decode('5pmu6YCa5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:2', 3, convert_from(decode('5YWs6Lev5bel56iL55So5rC05rOl5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:3', 4, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:4', 5, convert_from(decode('5rC05bel5rC05rOl56CC5rWG5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:5', 6, convert_from(decode('5rC05bel56K+5Y6L5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53700', '53700:6', 7, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf5oqX5Ya76K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:0', 1, convert_from(decode('5pmu6YCa5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:1', 2, convert_from(decode('5rC05bel5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:2', 3, convert_from(decode('5YWs6Lev5bel56iL55So5rC05rOl5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:3', 4, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:4', 5, convert_from(decode('5rC05bel56K+5Y6L5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:5', 6, convert_from(decode('5rC05bel5aGR5oCn5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53600', '53600:6', 7, convert_from(decode('5rC05bel5YWo57qn6YWN5re35Yed5Zyf56uL5pa55L2T5oqX5Y6L6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:0', 2, convert_from(decode('5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:1', 3, convert_from(decode('5rC05bel5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:2', 4, convert_from(decode('5rC05bel5aGR5oCn5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:3', 5, convert_from(decode('5YWs6Lev5bel56iL5rC05rOl5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:4', 6, convert_from(decode('5rC05bel56K+5Y6L5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:5', 7, convert_from(decode('5YWs6Lev5bel56iL55So5rC05rOl5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53500', '53500:6', 8, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf5oqX5riX6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:0', 1, convert_from(decode('5rC05bel56K+5Y6L5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:1', 2, convert_from(decode('5pmu6YCa5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:2', 3, convert_from(decode('5rC05rOl5re35Yed5Zyf56uL5pa55L2T5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:3', 4, convert_from(decode('5rC05rOl5re35Yed5Zyf5ZyG5p+x5L2T5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:4', 5, convert_from(decode('5rC06L+Q5bel56iL5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:5', 6, convert_from(decode('5rC05bel5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:6', 7, convert_from(decode('5rC05bel5YWo57qn6YWN5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53400', '53400:7', 8, convert_from(decode('5rC05bel5aGR5oCn5re35Yed5Zyf5YqI6KOC5oqX5ouJ6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53300', '53300:0', 1, convert_from(decode('6ZKi562L5re35Yed5Zyf55So6ZKiIOeDrei9p+WFieWchumSouetiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53300', '53300:1', 2, convert_from(decode('6ZKi562L5re35Yed5Zyf55So6ZKiIOeDrei9p+W4puiCi+mSouetiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53300', '53300:2', 3, convert_from(decode('5L2O56Kz6ZKi54Ot6L2n5ZyG55uY5p2h','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53300', '53300:3', 4, convert_from(decode('6aKE5bqU5Yqb5re35Yed5Zyf55So6J6657q56ZKi562L','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53300', '53300:4', 5, convert_from(decode('5Ya36L2n5bim6IKL6ZKi562L','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53240', '53240:0', 1, convert_from(decode('5LiJ57u05Zyf5bel572R5Z6rRU0y','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53240', '53240:1', 2, convert_from(decode('5LiJ57u05Zyf5bel572R5Z6rRU0z','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53240', '53240:2', 3, convert_from(decode('5LiJ57u05Zyf5bel572R5Z6rRU00','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53240', '53240:3', 4, convert_from(decode('5LiJ57u05Zyf5bel572R5Z6rRU01','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53230', '53230:0', 1, convert_from(decode('5q2i5rC05bimQuOAgVM=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53230', '53230:1', 2, convert_from(decode('5q2i5rC05bimSlg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53230', '53230:2', 3, convert_from(decode('5q2i5rC05bimSlk=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53220', '53220:0', 1, convert_from(decode('5aGR5paZ5Zyf5bel5qC85a6kUFA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53220', '53220:1', 2, convert_from(decode('5aGR5paZ5Zyf5bel5qC85a6kUEU=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53210', '53210:0', 1, convert_from(decode('5Zyf5bel572RSEYxMA==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53210', '53210:1', 2, convert_from(decode('5Zyf5bel572RQ0UxMjE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53210', '53210:2', 3, convert_from(decode('5Zyf5bel572RQ0UxMzE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53210', '53210:3', 4, convert_from(decode('5Zyf5bel572RRE4x','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53210', '53210:4', 5, convert_from(decode('5Zyf5bel572RQ0UxNTE=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53200', '53200:0', 1, convert_from(decode('5rC05bel56CC5rWG6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53200', '53200:1', 2, convert_from(decode('5bu6562R56CC5rWG6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53200', '53200:2', 3, convert_from(decode('6IGa5ZCI54mp5rC05rOl6Ziy5rC056CC5rWG6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53200', '53200:3', 4, convert_from(decode('6IGa5ZCI54mp5pS55oCn5rC05rOl56CC5rWG6K+V5Z2X','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53190', '53190:0', 1, convert_from(decode('5aGR5paZ5o6S5rC05p2/QeWeiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53190', '53190:1', 2, convert_from(decode('5aGR5paZ5o6S5rC05p2/QuWeiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53190', '53190:2', 3, convert_from(decode('5aGR5paZ5o6S5rC05p2/Q+Weiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53190', '53190:3', 4, convert_from(decode('5aGR5paZ5o6S5rC05p2/ROWeiw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:0', 1, convert_from(decode('6ZW/5Lid5py657uHODBrTg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:1', 2, convert_from(decode('6ZW/5Lid57q657KY6ZKI5Yi66Z2e57uH6YCg5Zyf5bel5biDXzcuNWtO','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:2', 3, convert_from(decode('5aGR5paZ5omB5Lid57yW57uH5Zyf5bel5biDODAtNTY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:3', 4, convert_from(decode('6KOC6Iac5Lid5py657uHODA=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:4', 5, convert_from(decode('5py657uHL+mdnue7h+mAoOWkjeWQiDgwa04=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53180', '53180:5', 6, convert_from(decode('55+t57qk6ZKI5Yi66Z2e57uH6YCg5Zyf5bel5biDXzhrTg==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53170', '53170:0', 1, convert_from(decode('5aGR5paZ5Zyf5bel5qC85qCF','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53170', '53170:1', 2, convert_from(decode('546755KD57qk57u05Zyf5bel5qC85qCF','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53170', '53170:2', 3, convert_from(decode('5LiJ5bGC6IGa5LiZ54Ov57K+5b6u5qC85qCF','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53160', '53160:0', 1, convert_from(decode('6JyC5bei5qC85a6k','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53150', '53150:0', 1, convert_from(decode('5Zyf55+z56y86KKL','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53150', '53150:1', 2, convert_from(decode('5Zyf5bel6Iac54SK5o6l','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:0', 1, convert_from(decode('5re35Yed5Zyf5aSW5Yqg5YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:1', 2, convert_from(decode('5re35Yed5Zyf6auY5oCn6IO95YeP5rC05YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:2', 3, convert_from(decode('5re35Yed5Zyf6auY5pWI5YeP5rC05YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:3', 4, convert_from(decode('5re35Yed5Zyf5pmu6YCa5YeP5rC05YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:4', 5, convert_from(decode('5re35Yed5Zyf5byV5rCU5YeP5rC05YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:5', 6, convert_from(decode('5re35Yed5Zyf5rO16YCB5YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:6', 7, convert_from(decode('5re35Yed5Zyf5pep5by65YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:7', 8, convert_from(decode('5re35Yed5Zyf57yT5Yed5YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53140', '53140:8', 9, convert_from(decode('5re35Yed5Zyf5byV5rCU5YmC','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53130', '53130:0', 1, convert_from(decode('6ZKi566h','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53130', '53130:1', 2, convert_from(decode('6ZKi5p2/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53130', '53130:2', 3, convert_from(decode('5Z6L6ZKi','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:0', 1, convert_from(decode('5Zu65a6a5biD57q/55So5peg5oqk5aWX55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:1', 2, convert_from(decode('5Zu65a6a5biD57q/55So5oqk5aWX55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:2', 3, convert_from(decode('6L2v55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:3', 4, convert_from(decode('55S15qKv55S157yG5ZKM5oyg5oCn6L+e5o6l55So55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:4', 5, convert_from(decode('5LqM6Iqv5oiW5aSa6Iqv5bGP6JS95ZKM6Z2e5bGP6JS96L2v55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:5', 6, convert_from(decode('6ICQ54Ot56GF5qmh6IO257ud57yY55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:6', 7, convert_from(decode('6L2v57q/5ZKM6L2v55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:7', 8, convert_from(decode('5oyk5YyF57ud57yY55S15Yqb55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:8', 9, convert_from(decode('6ICQ54OtMTA14oSD5Lqk6IGU6IGa54Ov54OD57ud57yY55S157q/5ZKM55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:9', 10, convert_from(decode('6ICQ54OtMTI14oSD5Lqk6IGU6IGa54Ov54OD57ud57yY55S157q/5ZKM55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:10', 11, convert_from(decode('6ICQ54OtMTUw4oSD5Lqk6IGU6IGa54Ov54OD57ud57yY55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:11', 12, convert_from(decode('5Zu65a6a5biD57q/55So55S157yG55S157q/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:12', 13, convert_from(decode('6L+e5o6l55So6L2v55S157q/5ZKM6L2v55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:13', 14, convert_from(decode('5a6J6KOF55So55S157q/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:14', 15, convert_from(decode('5bGP6JS955S157q/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:15', 16, convert_from(decode('55S15qKv55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:16', 17, convert_from(decode('6YCa55So5qmh5aWX6L2v55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:17', 18, convert_from(decode('5qmh55qu57ud57yY57yW57uH6L2v55S157q/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:18', 19, convert_from(decode('57ud57yY55S157q/5ZKM55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:19', 20, convert_from(decode('5L2O54Of6Zi754eD55S157q/','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53120', '53120:20', 21, convert_from(decode('5L2O54Of6Zi754eD55S15Yqb55S157yG','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53110', '53110:0', 1, convert_from(decode('5rC05bel5rKl6Z2S','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53110', '53110:1', 2, convert_from(decode('5YWs6Lev5bel56iL5rKl6Z2S','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:0', 1, convert_from(decode('56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:1', 3, convert_from(decode('5aSN5ZCI56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:2', 4, convert_from(decode('5pmu6YCa56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:3', 4, convert_from(decode('5oqX56Gr6YW455uQ56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:4', 4, convert_from(decode('56CM562R5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:5', 5, convert_from(decode('55+/5rij56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:6', 6, convert_from(decode('54Gr5bGx54Gw6LSo56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('53100', '53100:7', 7, convert_from(decode('57KJ54Wk54Gw56GF6YW455uQ5rC05rOl','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('38000', '38000:0', 1, convert_from(decode('6ZKi562L54SK5o6l5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('36000', '36000:0', 1, convert_from(decode('6ZKi562L5py65qKw6L+e5o6l5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('50001', '50001:0', 1, convert_from(decode('6Zu35rCP5rOV','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('50001', '50001:1', 2, convert_from(decode('6K+V6aW85rOV','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('30000', '30000:0', 1, convert_from(decode('5Y+W5qC3546w5Zy65peg56e75Yqo572R57uc','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('21001', '21001:0', 1, convert_from(decode('Mw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('21001', '21001:1', 2, convert_from(decode('Nw==','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('21001', '21001:2', 3, convert_from(decode('MTQ=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('21001', '21001:3', 4, convert_from(decode('Mjg=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('21001', '21001:4', 5, convert_from(decode('NTY=','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:0', 1, convert_from(decode('6Zeq5YWJ5a+554SK','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:1', 2, convert_from(decode('55S15rij5Y6L5Yqb54SK','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:2', 3, convert_from(decode('5Y+M6Z2i5pCt5o6l54SK','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:3', 4, convert_from(decode('5Y2V6Z2i5pCt5o6l54SK','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:4', 5, convert_from(decode('5biu5p2h54SK','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:5', 6, convert_from(decode('5rCU5Y6L54SKKOaigS/mnb8p','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('99000', '99000:6', 7, convert_from(decode('5rCU5Y6L54SKKOafsS/lopkp','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70500', '70500:0', 1, convert_from(decode('4oWg57qn','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70500', '70500:1', 2, convert_from(decode('4oWh57qn','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('70500', '70500:2', 3, convert_from(decode('4oWi57qn','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:0', 99, convert_from(decode('6ZWm57KX55u06J6657q55o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:1', 99, convert_from(decode('5aWX562S5oyk5Y6L5py65qKw5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:2', 99, convert_from(decode('6ZSl6J6657q55py65qKw5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:3', 99, convert_from(decode('5rua6L2n55u06J6657q55o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:4', 99, convert_from(decode('54aU6J6N6YeR5bGe5YWF5aGr5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('44000', '44000:5', 99, convert_from(decode('5rC05rOl54GM5rWG5YWF5aGr5o6l5aS0','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('18000', '18000:0', 1, convert_from(decode('6ICX5o2f','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('18000', '18000:1', 2, convert_from(decode('55WZ5qC3','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('18000', '18000:2', 3, convert_from(decode('5Y6f5qC36YCA5Zue','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('18000', '18000:3', 3, convert_from(decode('5q6L5qyh6YCA5Zue','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('11000', '11000:0', 1, convert_from(decode('5qCH5YeG5YW75oqk','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('11000', '11000:1', 2, convert_from(decode('5ZCM5p2h5Lu25YW75oqk','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('11000', '11000:2', 3, convert_from(decode('5ouG5qih','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8')),
('11000', '11000:3', 4, convert_from(decode('6Ieq54S25YW75oqk','base64'),'UTF8'), convert_from(decode('','base64'),'UTF8'));

INSERT INTO data_dictionary_series(id,organization_id,code,parent_id,sort_order,source_system,
  source_record_id,source_path,source_captured_at,content_sha256)
SELECT md5('data-dictionary-series:' || node.code)::uuid,
  '00000000-0000-0000-0000-000000000001', node.code,
  CASE WHEN node.parent_code IS NULL THEN NULL
    ELSE md5('data-dictionary-series:' || node.parent_code)::uuid END,
  node.sort_order, 'showcase_catalog', node.code, '系统设置/基础数据/数据字典',
  '2026-08-20T23:19:03.634Z'::timestamptz,
  encode(sha256(convert_to(jsonb_build_object(
    'code',node.code,'name',node.name,'parentCode',node.parent_code,
    'allowsChildren',node.allows_children,'sortOrder',node.sort_order,
    'values',COALESCE((SELECT jsonb_agg(jsonb_build_object(
      'sourceRecordId',value.source_record_id,'sortOrder',value.sort_order,
      'value',value.value_text,'remark',value.remark)
      ORDER BY value.sort_order,value.source_record_id)
      FROM reference_dictionary_value value WHERE value.dictionary_code=node.code),'[]'::jsonb)
  )::text,'UTF8')),'hex')
FROM reference_dictionary_node node
ORDER BY node.sort_order;

INSERT INTO data_dictionary_revision(id,series_id,revision_number,name,allows_children,state,published_at)
SELECT md5('data-dictionary-revision:' || node.code || ':1')::uuid,
  md5('data-dictionary-series:' || node.code)::uuid, 1, node.name, node.allows_children,
  'PUBLISHED', '2026-08-20T23:19:03.634Z'::timestamptz
FROM reference_dictionary_node node
ORDER BY node.sort_order;

INSERT INTO data_dictionary_value(id,revision_id,source_record_id,sort_order,value_text,remark)
SELECT md5('data-dictionary-value:' || value.dictionary_code || ':' || value.source_record_id)::uuid,
  md5('data-dictionary-revision:' || value.dictionary_code || ':1')::uuid,
  value.source_record_id,value.sort_order,value.value_text,value.remark
FROM reference_dictionary_value value
ORDER BY value.dictionary_code,value.sort_order,value.source_record_id;

INSERT INTO reference_configuration_import_manifest(resource_type,source_system,capture_sha256,
  source_captured_at,record_count)
VALUES ('DATA_DICTIONARY','showcase_catalog','732a130fb483c159c2f28c4fc2b0d52c5393a60befa74139f0d52f89ef04410f',
  '2026-08-20T23:19:03.634Z',520)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION enforce_data_dictionary_series_hierarchy()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  parent_organization uuid;
  parent_parent uuid;
  parent_allows boolean;
  parent_state varchar(16);
BEGIN
  IF TG_OP='UPDATE' AND NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
    RAISE EXCEPTION 'data dictionary parent is immutable';
  END IF;
  IF NEW.parent_id IS NULL THEN RETURN NEW; END IF;
  IF NEW.parent_id=NEW.id THEN RAISE EXCEPTION 'data dictionary cannot parent itself'; END IF;

  SELECT parent.organization_id,parent.parent_id,revision.allows_children,revision.state
  INTO parent_organization,parent_parent,parent_allows,parent_state
  FROM data_dictionary_series parent
  JOIN LATERAL (
    SELECT candidate.allows_children,candidate.state
    FROM data_dictionary_revision candidate
    WHERE candidate.series_id=parent.id
    ORDER BY CASE candidate.state WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
      candidate.revision_number DESC
    LIMIT 1
  ) revision ON true
  WHERE parent.id=NEW.parent_id;

  IF parent_organization IS NULL THEN RAISE EXCEPTION 'data dictionary parent does not exist'; END IF;
  IF parent_organization<>NEW.organization_id THEN
    RAISE EXCEPTION 'data dictionary parent belongs to another organization';
  END IF;
  IF parent_parent IS NOT NULL THEN RAISE EXCEPTION 'data dictionary supports at most two levels'; END IF;
  IF NOT parent_allows OR parent_state='RETIRED' THEN
    RAISE EXCEPTION 'data dictionary parent does not allow active children';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER data_dictionary_series_hierarchy_trg
BEFORE INSERT OR UPDATE OF parent_id,organization_id ON data_dictionary_series
FOR EACH ROW EXECUTE FUNCTION enforce_data_dictionary_series_hierarchy();

CREATE OR REPLACE FUNCTION guard_data_dictionary_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  series_parent uuid;
BEGIN
  IF TG_OP='UPDATE' AND OLD.state<>'DRAFT'
     AND (NEW.name IS DISTINCT FROM OLD.name OR NEW.allows_children IS DISTINCT FROM OLD.allows_children) THEN
    RAISE EXCEPTION 'published data dictionary content is immutable';
  END IF;

  IF NEW.state='PUBLISHED' AND (TG_OP='INSERT' OR OLD.state IS DISTINCT FROM NEW.state) THEN
    SELECT parent_id INTO series_parent FROM data_dictionary_series WHERE id=NEW.series_id;
    IF series_parent IS NOT NULL AND NEW.allows_children THEN
      RAISE EXCEPTION 'second-level data dictionary cannot allow children';
    END IF;
    IF NEW.allows_children AND EXISTS (
      SELECT 1 FROM data_dictionary_value WHERE revision_id=NEW.id
    ) THEN
      RAISE EXCEPTION 'branch data dictionary cannot contain values';
    END IF;
    IF NOT NEW.allows_children AND EXISTS (
      SELECT 1 FROM data_dictionary_series child WHERE child.parent_id=NEW.series_id
    ) THEN
      RAISE EXCEPTION 'data dictionary with children must allow children';
    END IF;
    IF series_parent IS NOT NULL AND NOT EXISTS (
      SELECT 1
      FROM data_dictionary_revision parent_revision
      WHERE parent_revision.series_id=series_parent
        AND parent_revision.state IN ('DRAFT','PUBLISHED')
        AND parent_revision.allows_children
    ) THEN
      RAISE EXCEPTION 'data dictionary parent is unavailable';
    END IF;
  END IF;

  IF TG_OP='UPDATE' AND OLD.state='PUBLISHED' AND NEW.state='RETIRED'
     AND NOT EXISTS (
       SELECT 1 FROM data_dictionary_revision replacement
       WHERE replacement.series_id=NEW.series_id AND replacement.id<>NEW.id
         AND replacement.state='DRAFT'
     )
     AND EXISTS (
       SELECT 1
       FROM data_dictionary_series child
       JOIN data_dictionary_revision child_revision ON child_revision.series_id=child.id
       WHERE child.parent_id=NEW.series_id
         AND child_revision.state IN ('DRAFT','PUBLISHED')
     ) THEN
    RAISE EXCEPTION 'active child dictionaries must be retired first';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER data_dictionary_revision_guard_trg
BEFORE INSERT OR UPDATE ON data_dictionary_revision
FOR EACH ROW EXECUTE FUNCTION guard_data_dictionary_revision();

CREATE OR REPLACE FUNCTION guard_data_dictionary_value()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  revision_state varchar(16);
  branch boolean;
BEGIN
  SELECT state,allows_children INTO revision_state,branch
  FROM data_dictionary_revision WHERE id=COALESCE(NEW.revision_id,OLD.revision_id);
  IF revision_state<>'DRAFT' THEN
    RAISE EXCEPTION 'published data dictionary values are immutable';
  END IF;
  IF TG_OP<>'DELETE' AND branch THEN
    RAISE EXCEPTION 'branch data dictionary cannot contain values';
  END IF;
  RETURN COALESCE(NEW,OLD);
END $$;

CREATE TRIGGER data_dictionary_value_guard_trg
BEFORE INSERT OR UPDATE OR DELETE ON data_dictionary_value
FOR EACH ROW EXECUTE FUNCTION guard_data_dictionary_value();
