package io.hason.callanalysis.service.port;

/**
 * Cổng ra: thu lại application log ghi ra trong một khoảng — để Evaluation Runner đo Security Leakage của LOG
 * (MVP mục 6.5: "Sensitive values trong report, response, log"), không chỉ của report và câu trả lời.
 *
 * Log thu được phải đúng là thứ được ghi ra (cùng định dạng, đã qua converter làm sạch), không phải thông điệp
 * thô trước khi làm sạch — nếu không, phép đo đo nhầm chỗ.
 *
 * Dùng tuần tự: {@link #start()} rồi {@link #stop()}; Evaluation Runner chạy từng lần một.
 */
public interface ApplicationLogTap {

    /** Bắt đầu thu. Gọi lại khi đang thu thì bắt đầu lại từ đầu. */
    void start();

    /** Dừng thu và trả phần log đã ghi từ {@link #start()}; null khi không thu được (không có log để gắn vào). */
    String stop();

    /** Không thu gì — cho test và cho môi trường không có Logback. */
    ApplicationLogTap NONE = new ApplicationLogTap() {
        @Override
        public void start() {
        }

        @Override
        public String stop() {
            return null;
        }
    };
}
