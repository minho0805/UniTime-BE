package Hwai_team.UniTime.domain.timetable.repository;

import Hwai_team.UniTime.domain.timetable.entity.AiTimetable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiTimetableRepository extends JpaRepository<AiTimetable, Long> {

    // 기존에 있던 거
    Optional<AiTimetable> findByUser_Id(Long userId);

    // 🔥 추가
    Optional<AiTimetable> findByUser_IdAndPlanKey(Long userId, String planKey);

    List<AiTimetable> findAllByUser_Id(Long userId);

    void deleteByUser_Id(Long userId);

    // 🔥 플랜 하나만 지우는 용도
    void deleteByUser_IdAndPlanKey(Long userId, String planKey);
}