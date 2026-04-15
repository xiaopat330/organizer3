package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.backup.AvActressBackupEntry;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiAvActressRepository implements AvActressRepository {

    private static final RowMapper<AvActress> MAPPER = (rs, ctx) -> AvActress.builder()
            .id(rs.getLong("id"))
            .volumeId(rs.getString("volume_id"))
            .folderName(rs.getString("folder_name"))
            .stageName(rs.getString("stage_name") != null ? rs.getString("stage_name") : rs.getString("folder_name"))
            .iafdId(rs.getString("iafd_id"))
            .headshotPath(rs.getString("headshot_path"))
            .akaNamesJson(rs.getString("aka_names_json"))
            .gender(rs.getString("gender"))
            .dateOfBirth(rs.getString("date_of_birth"))
            .dateOfDeath(rs.getString("date_of_death"))
            .birthplace(rs.getString("birthplace"))
            .nationality(rs.getString("nationality"))
            .ethnicity(rs.getString("ethnicity"))
            .hairColor(rs.getString("hair_color"))
            .eyeColor(rs.getString("eye_color"))
            .heightCm(rs.getObject("height_cm") != null ? rs.getInt("height_cm") : null)
            .weightKg(rs.getObject("weight_kg") != null ? rs.getInt("weight_kg") : null)
            .measurements(rs.getString("measurements"))
            .cup(rs.getString("cup"))
            .shoeSize(rs.getString("shoe_size"))
            .tattoos(rs.getString("tattoos"))
            .piercings(rs.getString("piercings"))
            .activeFrom(rs.getObject("active_from") != null ? rs.getInt("active_from") : null)
            .activeTo(rs.getObject("active_to") != null ? rs.getInt("active_to") : null)
            .directorFrom(rs.getObject("director_from") != null ? rs.getInt("director_from") : null)
            .directorTo(rs.getObject("director_to") != null ? rs.getInt("director_to") : null)
            .iafdTitleCount(rs.getObject("iafd_title_count") != null ? rs.getInt("iafd_title_count") : null)
            .websiteUrl(rs.getString("website_url"))
            .socialJson(rs.getString("social_json"))
            .platformsJson(rs.getString("platforms_json"))
            .externalRefsJson(rs.getString("external_refs_json"))
            .iafdCommentsJson(rs.getString("iafd_comments_json"))
            .awardsJson(rs.getString("awards_json"))
            .favorite(rs.getInt("favorite") == 1)
            .bookmark(rs.getInt("bookmark") == 1)
            .rejected(rs.getInt("rejected") == 1)
            .grade(rs.getString("grade"))
            .notes(rs.getString("notes"))
            .firstSeenAt(rs.getString("first_seen_at") != null
                    ? LocalDateTime.parse(rs.getString("first_seen_at")) : null)
            .lastScannedAt(rs.getString("last_scanned_at") != null
                    ? LocalDateTime.parse(rs.getString("last_scanned_at")) : null)
            .lastIafdSyncedAt(rs.getString("last_iafd_synced_at") != null
                    ? LocalDateTime.parse(rs.getString("last_iafd_synced_at")) : null)
            .videoCount(rs.getInt("video_count"))
            .totalSizeBytes(rs.getLong("total_size_bytes"))
            .visitCount(rs.getInt("visit_count"))
            .lastVisitedAt(rs.getString("last_visited_at"))
            .build();

    private final Jdbi jdbi;

    @Override
    public Optional<AvActress> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public Optional<AvActress> findByVolumeAndFolder(String volumeId, String folderName) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses WHERE volume_id = :volumeId AND folder_name = :folderName")
                        .bind("volumeId", volumeId)
                        .bind("folderName", folderName)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public List<AvActress> findByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses WHERE volume_id = :volumeId ORDER BY stage_name")
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public List<AvActress> findAllByVideoCountDesc() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses ORDER BY video_count DESC, stage_name")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public List<AvActress> findFavorites() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses WHERE favorite = 1 ORDER BY stage_name")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public long upsert(AvActress actress) {
        return jdbi.withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO av_actresses (
                        volume_id, folder_name, stage_name, first_seen_at,
                        video_count, total_size_bytes
                    ) VALUES (
                        :volumeId, :folderName, :stageName, :firstSeenAt,
                        :videoCount, :totalSizeBytes
                    ) ON CONFLICT(volume_id, folder_name) DO NOTHING
                    """)
                    .bind("volumeId", actress.getVolumeId())
                    .bind("folderName", actress.getFolderName())
                    .bind("stageName", actress.getStageName())
                    .bind("firstSeenAt", actress.getFirstSeenAt() != null
                            ? actress.getFirstSeenAt().toString()
                            : LocalDateTime.now().toString())
                    .bind("videoCount", actress.getVideoCount())
                    .bind("totalSizeBytes", actress.getTotalSizeBytes())
                    .execute();

            return h.createQuery(
                    "SELECT id FROM av_actresses WHERE volume_id = :volumeId AND folder_name = :folderName")
                    .bind("volumeId", actress.getVolumeId())
                    .bind("folderName", actress.getFolderName())
                    .mapTo(Long.class)
                    .one();
        });
    }

    @Override
    public void updateCounts(long actressId, int videoCount, long totalSizeBytes) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET video_count = :vc, total_size_bytes = :tsb WHERE id = :id")
                        .bind("vc", videoCount)
                        .bind("tsb", totalSizeBytes)
                        .bind("id", actressId)
                        .execute());
    }

    @Override
    public void updateLastScanned(long actressId, LocalDateTime at) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET last_scanned_at = :at WHERE id = :id")
                        .bind("at", at.toString())
                        .bind("id", actressId)
                        .execute());
    }

    @Override
    public void updateCuration(long actressId, boolean favorite, boolean bookmark,
                               boolean rejected, String grade, String notes) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE av_actresses SET
                            favorite = :favorite,
                            bookmark = :bookmark,
                            rejected = :rejected,
                            grade = :grade,
                            notes = :notes
                        WHERE id = :id
                        """)
                        .bind("favorite", favorite ? 1 : 0)
                        .bind("bookmark", bookmark ? 1 : 0)
                        .bind("rejected", rejected ? 1 : 0)
                        .bind("grade", grade)
                        .bind("notes", notes)
                        .bind("id", actressId)
                        .execute());
    }

    @Override
    public void toggleFavorite(long actressId, boolean favorite) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET favorite = :v WHERE id = :id")
                        .bind("v", favorite ? 1 : 0).bind("id", actressId).execute());
    }

    @Override
    public void toggleBookmark(long actressId, boolean bookmark) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET bookmark = :v WHERE id = :id")
                        .bind("v", bookmark ? 1 : 0).bind("id", actressId).execute());
    }

    @Override
    public void toggleRejected(long actressId, boolean rejected) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET rejected = :v WHERE id = :id")
                        .bind("v", rejected ? 1 : 0).bind("id", actressId).execute());
    }

    @Override
    public void setGrade(long actressId, String grade) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET grade = :grade WHERE id = :id")
                        .bind("grade", grade).bind("id", actressId).execute());
    }

    @Override
    public void updateStageName(long actressId, String stageName) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET stage_name = :name WHERE id = :id")
                        .bind("name", stageName).bind("id", actressId).execute());
    }

    @Override
    public List<AvActress> findUnresolved() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_actresses WHERE iafd_id IS NULL ORDER BY stage_name")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public void updateIafdFields(long actressId, IafdResolvedProfile p, String headshotPath) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE av_actresses SET
                            iafd_id              = :iafdId,
                            headshot_path        = :headshotPath,
                            aka_names_json       = :akaNamesJson,
                            gender               = :gender,
                            date_of_birth        = :dateOfBirth,
                            date_of_death        = :dateOfDeath,
                            birthplace           = :birthplace,
                            nationality          = :nationality,
                            ethnicity            = :ethnicity,
                            hair_color           = :hairColor,
                            eye_color            = :eyeColor,
                            height_cm            = :heightCm,
                            weight_kg            = :weightKg,
                            measurements         = :measurements,
                            cup                  = :cup,
                            shoe_size            = :shoeSize,
                            tattoos              = :tattoos,
                            piercings            = :piercings,
                            active_from          = :activeFrom,
                            active_to            = :activeTo,
                            director_from        = :directorFrom,
                            director_to          = :directorTo,
                            iafd_title_count     = :iafdTitleCount,
                            website_url          = :websiteUrl,
                            social_json          = :socialJson,
                            platforms_json       = :platformsJson,
                            external_refs_json   = :externalRefsJson,
                            iafd_comments_json   = :iafdCommentsJson,
                            awards_json          = :awardsJson,
                            last_iafd_synced_at  = :syncedAt
                        WHERE id = :id
                        """)
                        .bind("id", actressId)
                        .bind("iafdId", p.getIafdId())
                        .bind("headshotPath", headshotPath)
                        .bind("akaNamesJson", p.getAkaNamesJson())
                        .bind("gender", p.getGender())
                        .bind("dateOfBirth", p.getDateOfBirth())
                        .bind("dateOfDeath", p.getDateOfDeath())
                        .bind("birthplace", p.getBirthplace())
                        .bind("nationality", p.getNationality())
                        .bind("ethnicity", p.getEthnicity())
                        .bind("hairColor", p.getHairColor())
                        .bind("eyeColor", p.getEyeColor())
                        .bind("heightCm", p.getHeightCm())
                        .bind("weightKg", p.getWeightKg())
                        .bind("measurements", p.getMeasurements())
                        .bind("cup", p.getCup())
                        .bind("shoeSize", p.getShoeSize())
                        .bind("tattoos", p.getTattoos())
                        .bind("piercings", p.getPiercings())
                        .bind("activeFrom", p.getActiveFrom())
                        .bind("activeTo", p.getActiveTo())
                        .bind("directorFrom", p.getDirectorFrom())
                        .bind("directorTo", p.getDirectorTo())
                        .bind("iafdTitleCount", p.getIafdTitleCount())
                        .bind("websiteUrl", p.getWebsiteUrl())
                        .bind("socialJson", p.getSocialJson())
                        .bind("platformsJson", p.getPlatformsJson())
                        .bind("externalRefsJson", p.getExternalRefsJson())
                        .bind("iafdCommentsJson", p.getIafdCommentsJson())
                        .bind("awardsJson", p.getAwardsJson())
                        .bind("syncedAt", LocalDateTime.now().toString())
                        .execute());
    }

    @Override
    public void recordVisit(long actressId) {
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE av_actresses SET visit_count = visit_count + 1, last_visited_at = :now WHERE id = :id")
                        .bind("now", LocalDateTime.now().toString())
                        .bind("id", actressId)
                        .execute());
    }

    @Override
    public void migrateCuration(long fromActressId, long toActressId) {
        jdbi.useHandle(h -> {
            // Copy all non-identity fields from source to target
            h.createUpdate("""
                    UPDATE av_actresses SET
                        stage_name          = (SELECT stage_name          FROM av_actresses WHERE id = :from),
                        iafd_id             = (SELECT iafd_id             FROM av_actresses WHERE id = :from),
                        headshot_path       = (SELECT headshot_path       FROM av_actresses WHERE id = :from),
                        aka_names_json      = (SELECT aka_names_json      FROM av_actresses WHERE id = :from),
                        gender              = (SELECT gender              FROM av_actresses WHERE id = :from),
                        date_of_birth       = (SELECT date_of_birth       FROM av_actresses WHERE id = :from),
                        date_of_death       = (SELECT date_of_death       FROM av_actresses WHERE id = :from),
                        birthplace          = (SELECT birthplace          FROM av_actresses WHERE id = :from),
                        nationality         = (SELECT nationality         FROM av_actresses WHERE id = :from),
                        ethnicity           = (SELECT ethnicity           FROM av_actresses WHERE id = :from),
                        hair_color          = (SELECT hair_color          FROM av_actresses WHERE id = :from),
                        eye_color           = (SELECT eye_color           FROM av_actresses WHERE id = :from),
                        height_cm           = (SELECT height_cm           FROM av_actresses WHERE id = :from),
                        weight_kg           = (SELECT weight_kg           FROM av_actresses WHERE id = :from),
                        measurements        = (SELECT measurements        FROM av_actresses WHERE id = :from),
                        cup                 = (SELECT cup                 FROM av_actresses WHERE id = :from),
                        shoe_size           = (SELECT shoe_size           FROM av_actresses WHERE id = :from),
                        tattoos             = (SELECT tattoos             FROM av_actresses WHERE id = :from),
                        piercings           = (SELECT piercings           FROM av_actresses WHERE id = :from),
                        active_from         = (SELECT active_from         FROM av_actresses WHERE id = :from),
                        active_to           = (SELECT active_to           FROM av_actresses WHERE id = :from),
                        director_from       = (SELECT director_from       FROM av_actresses WHERE id = :from),
                        director_to         = (SELECT director_to         FROM av_actresses WHERE id = :from),
                        iafd_title_count    = (SELECT iafd_title_count    FROM av_actresses WHERE id = :from),
                        website_url         = (SELECT website_url         FROM av_actresses WHERE id = :from),
                        social_json         = (SELECT social_json         FROM av_actresses WHERE id = :from),
                        platforms_json      = (SELECT platforms_json      FROM av_actresses WHERE id = :from),
                        external_refs_json  = (SELECT external_refs_json  FROM av_actresses WHERE id = :from),
                        iafd_comments_json  = (SELECT iafd_comments_json  FROM av_actresses WHERE id = :from),
                        awards_json         = (SELECT awards_json         FROM av_actresses WHERE id = :from),
                        last_iafd_synced_at = (SELECT last_iafd_synced_at FROM av_actresses WHERE id = :from),
                        favorite            = (SELECT favorite            FROM av_actresses WHERE id = :from),
                        bookmark            = (SELECT bookmark            FROM av_actresses WHERE id = :from),
                        rejected            = (SELECT rejected            FROM av_actresses WHERE id = :from),
                        grade               = (SELECT grade               FROM av_actresses WHERE id = :from),
                        notes               = (SELECT notes               FROM av_actresses WHERE id = :from),
                        visit_count         = (SELECT visit_count         FROM av_actresses WHERE id = :from),
                        last_visited_at     = (SELECT last_visited_at     FROM av_actresses WHERE id = :from)
                    WHERE id = :to
                    """)
                    .bind("from", fromActressId)
                    .bind("to", toActressId)
                    .execute();
            // Delete the orphaned source row (its av_videos were already orphaned by sync)
            h.createUpdate("DELETE FROM av_actresses WHERE id = :id")
                    .bind("id", fromActressId).execute();
        });
    }

    @Override
    public void delete(long actressId) {
        jdbi.useTransaction(h -> {
            // Delete child rows first (foreign_keys may not be enforced)
            h.execute("DELETE FROM av_video_tags  WHERE av_video_id IN (SELECT id FROM av_videos WHERE av_actress_id = ?)", actressId);
            h.execute("DELETE FROM av_screenshots WHERE av_video_id IN (SELECT id FROM av_videos WHERE av_actress_id = ?)", actressId);
            h.execute("DELETE FROM av_videos WHERE av_actress_id = ?", actressId);
            h.execute("DELETE FROM av_actresses WHERE id = ?", actressId);
        });
    }

    @Override
    public List<AvActressBackupRow> findAllForBackup() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT volume_id, folder_name, favorite, bookmark, rejected,
                               grade, notes, visit_count, last_visited_at
                        FROM av_actresses
                        WHERE favorite = 1
                           OR bookmark = 1
                           OR rejected = 1
                           OR grade IS NOT NULL
                           OR notes IS NOT NULL
                           OR visit_count > 0
                        ORDER BY volume_id, folder_name
                        """)
                .map((rs, ctx) -> new AvActressBackupRow(
                        rs.getString("volume_id"),
                        rs.getString("folder_name"),
                        rs.getInt("favorite") == 1,
                        rs.getInt("bookmark") == 1,
                        rs.getInt("rejected") == 1,
                        rs.getString("grade"),
                        rs.getString("notes"),
                        rs.getInt("visit_count"),
                        rs.getString("last_visited_at")))
                .list());
    }

    @Override
    public void restoreUserData(String volumeId, String folderName, boolean favorite, boolean bookmark,
                                boolean rejected, String grade, String notes,
                                int visitCount, String lastVisitedAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE av_actresses SET
                            favorite         = :favorite,
                            bookmark         = :bookmark,
                            rejected         = :rejected,
                            grade            = :grade,
                            notes            = :notes,
                            visit_count      = :visitCount,
                            last_visited_at  = :lastVisitedAt
                        WHERE volume_id = :volumeId AND folder_name = :folderName
                        """)
                .bind("favorite", favorite ? 1 : 0)
                .bind("bookmark", bookmark ? 1 : 0)
                .bind("rejected", rejected ? 1 : 0)
                .bind("grade", grade)
                .bind("notes", notes)
                .bind("visitCount", visitCount)
                .bind("lastVisitedAt", lastVisitedAt)
                .bind("volumeId", volumeId)
                .bind("folderName", folderName)
                .execute());
    }

    @Override
    public List<FederatedAvActressResult> searchForFederated(String query, int limit) {
        String pattern = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT id, stage_name, video_count, headshot_path
                        FROM av_actresses
                        WHERE stage_name LIKE :pattern ESCAPE '\\'
                          AND rejected = 0
                        ORDER BY video_count DESC
                        LIMIT :limit
                        """)
                .bind("pattern", pattern)
                .bind("limit", limit)
                .map((rs, ctx) -> new FederatedAvActressResult(
                        rs.getLong("id"),
                        rs.getString("stage_name"),
                        rs.getInt("video_count"),
                        rs.getString("headshot_path")))
                .list());
    }
}
