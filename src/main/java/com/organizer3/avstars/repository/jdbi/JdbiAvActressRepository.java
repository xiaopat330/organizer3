package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
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
    public void migrateCuration(long fromActressId, long toActressId) {
        jdbi.useHandle(h -> {
            // Copy curation + IAFD identity from source to target
            h.createUpdate("""
                    UPDATE av_actresses
                    SET favorite     = (SELECT favorite     FROM av_actresses WHERE id = :from),
                        bookmark     = (SELECT bookmark     FROM av_actresses WHERE id = :from),
                        rejected     = (SELECT rejected     FROM av_actresses WHERE id = :from),
                        grade        = (SELECT grade        FROM av_actresses WHERE id = :from),
                        notes        = (SELECT notes        FROM av_actresses WHERE id = :from),
                        iafd_id      = (SELECT iafd_id      FROM av_actresses WHERE id = :from),
                        headshot_path = (SELECT headshot_path FROM av_actresses WHERE id = :from)
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
}
