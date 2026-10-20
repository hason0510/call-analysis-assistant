package io.hason.callanalysis.domain.timeline;

/**
 * Ghi chú về quá trình dựng timeline. Những note loại DATA_LIMITATION sẽ đi thẳng
 * vào mục "Giới hạn dữ liệu" của report.
 */
public record TimelineNote(Kind kind, String message) {

    public enum Kind {
        /** Đã loại bỏ bản ghi trùng lặp. */
        DEDUPED,
        /** Độ lệch đồng hồ giữa client và server đo được. */
        CLOCK_OFFSET,
        /** Track dùng mốc thời gian tương đối, không ghép được vào timeline chính. */
        RELATIVE_TRACK,
        /**
         * Leg của một file KHÔNG khớp với điều tên file gợi ý, gồm hai trường hợp: file đã
         * được gán lại leg theo nội dung (tên file sai, ví dụ calleer_webrtc.log), hoặc chưa
         * đối chiếu được chủ sở hữu theo nội dung. Cả hai đều phải nêu ở "Giới hạn dữ liệu"
         * để người đọc không tin nhầm vào tên file.
         */
        LEG_UNCERTAIN,
        /**
         * Bản export signaling bị cắt bớt: nguồn trả về ít event hơn số thực có.
         *
         * Tách riêng khỏi DATA_LIMITATION vì tầng rule cần phân biệt "thiếu event
         * signaling" với các thiếu hụt khác — chỉ trường hợp này mới được kết luận
         * là bản export bị cắt.
         */
        SIGNALING_TRUNCATED,
        /**
         * File đính kèm bị loại vì thuộc cuộc gọi KHÁC (end call log lệch Call-ID, hoặc WebRTC log đi cùng lượt tải lên
         * chỉ có log của cuộc khác). Tách khỏi DATA_LIMITATION vì report phải nói thẳng nguyên nhân này: khi mọi log đều
         * bị loại vì lý do đó, "không có log client" là sai — người dùng có gửi, chỉ là của cuộc khác (thường do gõ nhầm
         * Call-ID; Peer Evaluation 2026-10-20).
         */
        FOREIGN_CALL_FILE,
        /** Thiếu dữ liệu, ảnh hưởng đến kết luận. */
        DATA_LIMITATION
    }

    public static TimelineNote of(Kind kind, String message) {
        return new TimelineNote(kind, message);
    }
}
