// src/main/java/Hwai_team/UniTime/domain/timetable/service/TimetableService.java
package Hwai_team.UniTime.domain.timetable.service;

import Hwai_team.UniTime.domain.course.entity.Course;
import Hwai_team.UniTime.domain.course.repository.CourseRepository;
import Hwai_team.UniTime.domain.timetable.dto.*;
import Hwai_team.UniTime.domain.timetable.entity.Timetable;
import Hwai_team.UniTime.domain.timetable.entity.TimetableItem;
import Hwai_team.UniTime.domain.timetable.repository.TimetableItemRepository;
import Hwai_team.UniTime.domain.timetable.repository.TimetableRepository;
import Hwai_team.UniTime.domain.user.entity.User;
import Hwai_team.UniTime.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Setter
@Service
@RequiredArgsConstructor
public class TimetableService {

    private final AiTimetableService aiTimetableService;
    private final TimetableItemRepository timetableItemRepository;
    private final TimetableRepository timetableRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;

    /**
     * ✅ AI 시간표 생성
     * - 프론트에서 body로 userId, message, year, semester를 보내줌
     * - 여기서 AiTimetableService.createByAi() 호출해서 Timetable 생성
     * - 생성된 Timetable을 TimetableResponse 형태로 바로 반환 (FE의 AIGenerateTimetableResponse와 구조 호환)
     */
    @Transactional
    public TimetableResponse generateAiTimetable(AiTimetableRequest request) {

        Long userId = request.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다. id=" + userId));

        // AI 시간표 실제 생성 (여기서 AiTimetable 엔티티까지 같이 관리)
        Timetable timetable = aiTimetableService.createByAi(request);

        // 프론트가 원하는 건 '방금 만들어진 시간표 내용'
        return TimetableResponse.from(timetable);
    }

    /** 내 시간표 목록 조회 */
    @Transactional(readOnly = true)
    public List<TimetableResponse> getMyTimetables(Long userId) {
        List<Timetable> timetables = timetableRepository.findByOwner_IdOrderByYearDescSemesterDesc(userId);
        return timetables.stream().map(TimetableResponse::from).collect(Collectors.toList());
    }

    /** 빈 시간표 생성 */
    @Transactional
    public TimetableResponse createTimetable(Long userId, TimetableCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다. id=" + userId));

        if (request.getYear() == null || request.getSemester() == null) {
            throw new IllegalArgumentException("year, semester는 필수입니다.");
        }

        String title = (request.getTitle() == null || request.getTitle().isBlank())
                ? String.format("%d학년도 %d학기", request.getYear(), request.getSemester())
                : request.getTitle();

        Timetable timetable = Timetable.builder()
                .owner(user)
                .year(request.getYear())
                .semester(request.getSemester())
                .title(title)
                .build();

        Timetable saved = timetableRepository.save(timetable);
        return TimetableResponse.from(saved);
    }

    /**
     * ✅ 시간표 수정 (제목 + 아이템 전체 교체)
     * - 이미지에서 가져온 강의들은 courseId가 없을 수 있으므로
     *   courseId가 null인 경우 Course를 조회하지 않고, 이름/요일/교시만으로 저장 허용
     * - 잘못된 아이템 하나 때문에 전체가 500으로 터지지 않게,
     *   문제 있는 아이템만 건너뛰도록 방어 코드 추가
     */
    @Transactional
    public TimetableResponse updateTimetable(Long timetableId, TimetableUpdateRequest request) {

        Timetable timetable = timetableRepository.findById(timetableId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 시간표입니다. id=" + timetableId));

        // 제목 변경
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            timetable.changeTitle(request.getTitle());
        }

        // 아이템 전체 교체 모드
        if (request.getItems() != null) {
            // 1) 기존 아이템 DB에서 삭제
            timetableItemRepository.deleteByTimetable_Id(timetableId);

            // 2) 엔티티 내부 컬렉션도 정리 (양방향 일관성 유지)
            if (timetable.getItems() != null) {
                timetable.getItems().clear();
            }

            // 3) 요청 아이템으로 새로 구성
            for (TimetableUpdateRequest.Item it : request.getItems()) {

                // ✅ courseId가 있을 때만 Course 조회
                Course course = null;
                if (it.getCourseId() != null) {
                    // 못 찾으면 예외 던지지 말고 null 처리 (임시/옛날 과목 보호)
                    course = courseRepository.findById(it.getCourseId()).orElse(null);
                }

                // ✅ 우선순위: 요청값 > DB(course) 값
                String dayOfWeek  = it.getDayOfWeek();
                Integer start     = it.getStartPeriod();
                Integer end       = it.getEndPeriod();
                String room       = it.getRoom();
                String category   = it.getCategory();
                String courseName = it.getCourseName();

                if (course != null) {
                    if (dayOfWeek == null) dayOfWeek = course.getDayOfWeek();
                    if (start == null)     start     = course.getStartPeriod();
                    if (end == null)       end       = course.getEndPeriod();
                    if (room == null)      room      = course.getRoom();
                    if (category == null)  category  = course.getCategory();
                    if (courseName == null || courseName.isBlank()) {
                        courseName = course.getName();
                    }
                }

                // ❗ 시간 정보가 완전 없으면 이 아이템만 스킵 (전체 500 방지)
                if (dayOfWeek == null || start == null || end == null) {
                    // 필요하면 여기서 log.warn 찍어도 됨
                    continue;
                }

                // ❗ 과목 이름도 없으면 스킵
                if (courseName == null || courseName.isBlank()) {
                    continue;
                }

                TimetableItem item = TimetableItem.builder()
                        .timetable(timetable)
                        .course(course)        // ✅ 이미지에서 온 건 null일 수 있음
                        .courseName(courseName)
                        .category(category)
                        .dayOfWeek(dayOfWeek)
                        .startPeriod(start)
                        .endPeriod(end)
                        .room(room)
                        .build();

                try {
                    // ✅ 먼저 엔티티에 추가해서 충돌 검사
                    timetable.addItem(item);
                } catch (IllegalStateException e) {
                    // 시간표 충돌 나는 수업은 건너뜀 (전체 저장 실패 방지)
                    continue;
                }

                timetableItemRepository.save(item);
            }
        }

        return TimetableResponse.from(timetable);
    }

    /** 시간표 삭제 */
    @Transactional
    public void deleteTimetable(Long timetableId) {
        timetableItemRepository.deleteByTimetable_Id(timetableId);
        timetableRepository.deleteById(timetableId);
    }
}