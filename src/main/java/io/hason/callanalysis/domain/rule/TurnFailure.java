package io.hason.callanalysis.domain.rule;

import java.util.Objects;

/**
 * TURN không cấp phát được, phân kiểu theo HAI con số đọc thẳng từ WebRTC log: số request
 * allocate đã gửi và số phản hồi nhận về từ TURN server.
 *
 * Tách kiểu vì mỗi kiểu dẫn tới một hướng điều tra khác hẳn nhau. Đề xuất chung "kiểm tra
 * credential / cổng UDP 3478" chỉ đúng khi request thật sự đã rời máy; với file không tạo
 * nổi socket TURN thì cả hai đều sai hướng.
 *
 * Mỗi kiểu dưới đây có ít nhất một ca trong fail/ (tập có nhãn):
 *   SOCKET_NOT_CREATED     703100CF  8 × Failed to create TURN client socket, 0 request gửi đi
 *   SEND_FAILED_ON_DEVICE  7B56D7AD  20 request, 40 × Failed to send TURN message, error: 65
 *   NO_RESPONSE            E9D6C112  40 request, 0 phản hồi
 * NOT_ALLOCATED_AFTER_RESPONSE chưa có ca mẫu nào — nhánh viết cho đủ, chưa kiểm chứng.
 *
 * Không dựa vào `Failed to send TURN message` hay `TURN probe ... timeout` ĐỨNG RIÊNG:
 * cả hai đều có ở cuộc gọi thành công (Failed to send ở 1/13 file WebRTC của success/,
 * probe timeout ở 5/13). Chúng chỉ được xét trong file đã có 0 lần allocate thành công.
 *
 * @param requestsSent số dòng `TURN allocate request sent` của file hỏng
 * @param responses    số dòng `allocate error response` (401 đòi xác thực và các mã khác)
 * @param sendError    mã lỗi NGUYÊN VĂN của dòng `Failed to send TURN message` đầu tiên,
 *                     dạng "error: 65"; null nếu không có dòng đó. Không diễn giải mã:
 *                     cùng một số errno mang nghĩa khác nhau giữa iOS và Android.
 * @param transport    cổng và giao thức TURN đọc từ log, dạng "3478/udp"; null nếu không đọc được
 * @param vpnInterface tên giao diện VPN libwebrtc liệt kê trong cùng file, dạng "tun0";
 *                     null nếu không có. Chỉ là DỮ KIỆN đi kèm, không phải nguyên nhân:
 *                     mới thấy trên đúng 1 máy (703100CF, 0A6C2821, 45AA3011 cùng deviceId).
 */
public record TurnFailure(
        Kind kind,
        int requestsSent,
        int responses,
        String sendError,
        String transport,
        String vpnInterface
) {

    public enum Kind {
        /** TURN cấp phát được, hoặc cuộc gọi không có hoạt động TURN nào. */
        NONE,
        /** Không tạo được socket TURN, chưa gửi request nào: server và tường lửa chưa hề được chạm tới. */
        SOCKET_NOT_CREATED,
        /** Request lỗi ngay khi gửi trên thiết bị, không có phản hồi nào. */
        SEND_FAILED_ON_DEVICE,
        /** Request đã gửi đi nhưng không có phản hồi nào: log không phân biệt được mạng chặn hay server im lặng. */
        NO_RESPONSE,
        /** Server có phản hồi nhưng không lần nào cấp phát. Chưa có ca mẫu. */
        NOT_ALLOCATED_AFTER_RESPONSE,
        /** Không cấp phát được nhưng log không đủ để xếp vào kiểu nào ở trên. */
        UNCLASSIFIED
    }

    public static final TurnFailure NONE = new TurnFailure(Kind.NONE, 0, 0, null, null, null);

    public TurnFailure {
        Objects.requireNonNull(kind, "kind");
    }

    public boolean failed() {
        return kind != Kind.NONE;
    }

    /** Hai kiểu chưa có ca mẫu có nhãn nào: hướng điều tra của chúng chưa được kiểm chứng. */
    public boolean unvalidated() {
        return kind == Kind.NOT_ALLOCATED_AFTER_RESPONSE || kind == Kind.UNCLASSIFIED;
    }
}
