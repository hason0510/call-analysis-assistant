package io.hason.callanalysis.domain.validation;

import io.hason.callanalysis.domain.parse.DetectedLogType;
import io.hason.callanalysis.domain.parse.EndCallLogParser;
import io.hason.callanalysis.domain.parse.FileTypeDetector;
import io.hason.callanalysis.domain.validation.FileValidation.Reason;
import io.hason.callanalysis.domain.validation.FileValidation.RejectedFile;
import io.hason.callanalysis.domain.validation.FileValidation.ValidFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File Validator (MVP mục 6.1 T3, ca kiểm thử F02-F04 mục 6.4).
 *
 * Mỗi file xét theo thứ tự: quá lớn → rỗng → hỏng (nhị phân) → không nhận diện được loại →
 * end call log mang Call-ID khác. File không đạt bị LOẠI khỏi phân tích và nêu tên ở "Giới hạn
 * dữ liệu" — không bỏ im lặng, cũng không phân tích cùng. Bản Sprint 1 chỉ cảnh báo file lệch
 * Call-ID mà vẫn đưa nó vào timeline, nên log của cuộc gọi khác góp vào verdict.
 *
 * Không kiểm ở đây:
 * - Thiếu file của một leg (F01): leg của WebRTC log chỉ biết sau khi parse (vai SDP), nên
 *   ReportBuilder báo thiếu sau bước gán leg.
 * - Call-ID của WebRTC log: libwebrtc không ghi Call-ID của ứng dụng (ParseContext).
 *
 * Thuần domain: nhận nội dung đã đọc, không đụng đĩa hay mạng.
 */
public class FileValidator {

    /** File mẫu lớn nhất ~0,9 MB; 20 MB là dư hơn 20 lần mà vẫn chặn được file bất thường. */
    public static final long DEFAULT_MAX_BYTES_PER_FILE = 20L * 1024 * 1024;

    /** Số dòng không rỗng đầu file dùng để nhận ra file nhị phân. */
    private static final int CORRUPT_SCAN_LINES = 60;
    /** Tỉ lệ ký tự thay thế (U+FFFD, sinh ra khi giải mã UTF-8 hỏng) vượt mức này thì coi là hỏng. */
    private static final double MAX_REPLACEMENT_RATIO = 0.05;

    private final long maxBytesPerFile;
    private final FileTypeDetector detector = new FileTypeDetector();

    public FileValidator() {
        this(DEFAULT_MAX_BYTES_PER_FILE);
    }

    public FileValidator(long maxBytesPerFile) {
        if (maxBytesPerFile < 1) {
            throw new IllegalArgumentException("maxBytesPerFile phải >= 1: " + maxBytesPerFile);
        }
        this.maxBytesPerFile = maxBytesPerFile;
    }

    /**
     * @param callId Call-ID đang phân tích; null/rỗng thì lấy theo end call log (xem resolveCallId)
     */
    public FileValidation validate(String callId, List<AttachedFile> files) {
        // Sắp theo tên để kết quả tất định, không phụ thuộc thứ tự người dùng chọn file.
        List<AttachedFile> ordered = files.stream()
                .sorted(Comparator.comparing(AttachedFile::name))
                .toList();

        List<RejectedFile> rejected = new ArrayList<>();
        List<ValidFile> recognized = new ArrayList<>();
        for (AttachedFile file : ordered) {
            Optional<RejectedFile> problem = checkContent(file);
            if (problem.isPresent()) {
                rejected.add(problem.get());
                continue;
            }
            DetectedLogType type = detector.detect(file.lines());
            if (type == DetectedLogType.UNKNOWN) {
                rejected.add(new RejectedFile(file.name(), Reason.UNRECOGNIZED,
                        "không nhận diện được loại log (không phải end call log hay WebRTC log)"));
                continue;
            }
            recognized.add(new ValidFile(file.name(), type, file.lines()));
        }

        String resolved = callId == null || callId.isBlank() ? resolveCallId(recognized) : callId;

        List<ValidFile> accepted = new ArrayList<>();
        for (ValidFile file : recognized) {
            Optional<String> declared = file.type() == DetectedLogType.ENDCALL_LOG
                    ? EndCallLogParser.declaredCallId(file.lines())
                    : Optional.empty();
            if (resolved != null && declared.isPresent() && !declared.get().equals(resolved)) {
                rejected.add(new RejectedFile(file.name(), Reason.CALL_ID_MISMATCH,
                        "Call-ID trong file (" + declared.get() + ") khác Call-ID đang phân tích ("
                                + resolved + ")"));
                continue;
            }
            accepted.add(file);
        }
        rejected.sort(Comparator.comparing(RejectedFile::name));
        return new FileValidation(resolved, accepted, rejected);
    }

    private Optional<RejectedFile> checkContent(AttachedFile file) {
        if (file.sizeBytes() > maxBytesPerFile) {
            return Optional.of(new RejectedFile(file.name(), Reason.TOO_LARGE,
                    "dung lượng " + megabytes(file.sizeBytes()) + " vượt giới hạn "
                            + megabytes(maxBytesPerFile)));
        }
        if (!file.loaded()) {
            return Optional.of(new RejectedFile(file.name(), Reason.CORRUPT, "không đọc được nội dung"));
        }
        if (file.lines().stream().allMatch(String::isBlank)) {
            return Optional.of(new RejectedFile(file.name(), Reason.EMPTY, "file rỗng"));
        }
        if (looksBinary(file.lines())) {
            return Optional.of(new RejectedFile(file.name(), Reason.CORRUPT,
                    "file hỏng hoặc không phải văn bản (có ký tự nhị phân)"));
        }
        return Optional.empty();
    }

    /**
     * Ký tự NUL không bao giờ có trong log văn bản; nhiều ký tự thay thế U+FFFD nghĩa là
     * nội dung không phải UTF-8. Chỉ xét phần đầu file, đủ để nhận ra file nhị phân.
     */
    private static boolean looksBinary(List<String> lines) {
        long chars = 0;
        long replacement = 0;
        int scanned = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.indexOf('\u0000') >= 0) {
                return true;
            }
            chars += line.length();
            replacement += line.chars().filter(c -> c == '�').count();
            if (++scanned >= CORRUPT_SCAN_LINES) {
                break;
            }
        }
        return chars > 0 && (double) replacement / chars > MAX_REPLACEMENT_RATIO;
    }

    /**
     * Không ai nói Call-ID (Chat API, người dùng không ghi trong câu hỏi): lấy Call-ID mà các end
     * call log khai báo. Nhiều Call-ID khác nhau thì lấy cái nhiều file khai báo nhất, hoà thì cái
     * của file đứng trước theo tên — tất định; các file còn lại bị loại vì lệch Call-ID.
     */
    private static String resolveCallId(List<ValidFile> recognized) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (ValidFile file : recognized) {
            if (file.type() == DetectedLogType.ENDCALL_LOG) {
                EndCallLogParser.declaredCallId(file.lines()).ifPresent(id -> votes.merge(id, 1, Integer::sum));
            }
        }
        String best = null;
        int bestVotes = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes) {     // lớn hơn hẳn mới thay: hoà thì giữ cái đứng trước
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        return best;
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
