package cn.qingye.api;
import cn.qingye.business.Problem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    private ResponseEntity<?> error(int code,String message) {
        return ResponseEntity.status(code).body(Map.of("code",code,"message",message));
    }
    @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem e) {
        return error(e.status(),e.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> input(Exception e) {
        return error(400,"请检查必填项、数量和时间格式");
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(DataAccessException e) {
        return error(503,"数据库操作暂未完成，请稍后重试");
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed ({})",e.getClass().getSimpleName());
        return error(500,"操作未完成，请稍后重试");
    }
}
