-- places: 영어 장소명, 영어 주소 추가 (상세 화면에서 한국어와 같이 보여준다)
-- 구글에서 영어 값을 받지 못했거나 한국어 값과 같으면 NULL이다.
ALTER TABLE places
  ADD COLUMN name_en    VARCHAR(200) NULL COMMENT '영어 장소명 (없으면 NULL)' AFTER name,
  ADD COLUMN address_en VARCHAR(300) NULL COMMENT '영어 주소 (없으면 NULL)' AFTER address;
