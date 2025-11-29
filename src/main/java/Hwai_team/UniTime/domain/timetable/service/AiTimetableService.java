package Hwai_team.UniTime.domain.timetable.service;

import Hwai_team.UniTime.domain.course.entity.Course;
import Hwai_team.UniTime.domain.course.repository.CourseRepository;
import Hwai_team.UniTime.domain.timetable.dto.AiTimetableRequest;
import Hwai_team.UniTime.domain.timetable.dto.AiTimetableResponse;
import Hwai_team.UniTime.domain.timetable.dto.AiTimetableSaveRequest;
import Hwai_team.UniTime.domain.timetable.dto.TimetableSummaryResponse;
import Hwai_team.UniTime.domain.timetable.entity.AiTimetable;
import Hwai_team.UniTime.domain.timetable.entity.Timetable;
import Hwai_team.UniTime.domain.timetable.entity.TimetableItem;
import Hwai_team.UniTime.domain.timetable.repository.AiTimetableRepository;
import Hwai_team.UniTime.domain.timetable.repository.TimetableItemRepository;
import Hwai_team.UniTime.domain.timetable.repository.TimetableRepository;
import Hwai_team.UniTime.domain.user.entity.User;
import Hwai_team.UniTime.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Setter
@Service
@RequiredArgsConstructor
public class AiTimetableService {

    private static final int MAX_CREDITS = 19;                 // 시간표 최대 학점
    private static final int MAX_MAJOR_COUNT = 4;              // 최대 전공 수

    private final AiTimetableRepository aiTimetableRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final TimetableRepository timetableRepository;
    private final TimetableItemRepository timetableItemRepository;

    /**
     * <AI 시간표 생성 서비스>
     * AI가 사용자의 자연어를 토대로 시간표를 생성합니다.
     *
     * @author 김민호
     * @param {AiTimetableRequest}
     * @return {timetable}
     */
    @Transactional
    public Timetable createByAi(AiTimetableRequest request) {

        // 유저 정보
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다. id=" + request.getUserId()));

        final String userDeptNorm = normDept(user.getDepartment());       // 사용자 학과
        final Integer userGrade = user.getGrade();                        // 사용자 학년
        final String planKey = normalizePlanKey(request.getPlanKey());

        // 사용자가 작성한 자연어 메세지
        final String rawMessage = request.getMessage();
        final String summary = nullToEmpty(rawMessage);              // 파싱용 문자열

        // 사용자의 원하는 조건 요일
        final Integer maxDays = parseMaxDays(summary).orElse(null);  // "주 3일" 등
        final boolean avoidFirstPeriod = detectAvoidFirstPeriod(summary); // 1교시 회피 여부
        final Set<String> offDays = parseOffDays(summary);                // "금요일 공강" 등 공강 요일

        // 전체 과목 리스트 불러오기
        List<Course> all = courseRepository.findAll();

        // 채팅 안에서 타겟 학과/학년 추론
        final String targetDeptNorm = resolveTargetDepartment(summary, userDeptNorm, all);    // 정규화된 학과
        final Integer targetGrade = resolveTargetGrade(summary, userGrade);                   // 학년 (없으면 유저 학년)

        // 재수강 후보(메시지 내 이름/코드 포함)
        List<Course> retake = pickRetakeCourses(summary, all).stream()
                .filter(c -> !offDays.contains(nullToEmpty(c.getDayOfWeek())))             // 공강 요일 제외
                .collect(Collectors.toList());

        // 전공/교양 후보 필터링
        List<Course> major = all.stream()
                .filter(this::isMajorCategory)  // 전공/전심/전선/전필 등
                .filter(c -> deptStrictMatch(targetDeptNorm, normDept(c.getDepartment()))) // 학과 완전 일치
                .filter(c -> isMajorRecommendedGradeMatch(targetGrade, c))                 // 추천 학년 == 타겟 학년
                .filter(c -> !offDays.contains(nullToEmpty(c.getDayOfWeek())))            // 공강 요일 제외
                .collect(Collectors.toList());

        List<Course> liberal = all.stream()
                .filter(this::isLiberalCategory)
                .filter(c -> !offDays.contains(nullToEmpty(c.getDayOfWeek())))            // 공강 요일 제외
                .collect(Collectors.toList());

        // 시간표 엔티티 생성
        Timetable timetable = Timetable.builder()
                .owner(user)
                .year(request.getYear())
                .semester(request.getSemester())
                .title(buildTitle(user, request))
                .items(new ArrayList<>())
                .build();
        timetableRepository.save(timetable);

        int totalCredits = 0;
        Set<String> usedCourseCodes = new HashSet<>();
        Map<String, List<int[]>> occupied = new HashMap<>(); // day -> [start,end)
        Set<String> usedDays = new HashSet<>();
        Set<String> usedCourseNames = new HashSet<>();       // 이름 중복 방지용

        CourseAdder adder = new CourseAdder(
                timetable,
                timetableItemRepository,
                usedCourseCodes,
                occupied,
                usedDays,
                usedCourseNames
        );

        // 전공은 시간 빠른 순, 교양은 1교시 회피 옵션 반영
        major.sort(byStartPeriodWithAvoid(false));
        liberal.sort(byStartPeriodWithAvoid(avoidFirstPeriod));

        // 우선순위1: 재수강
        totalCredits = adder.addCourses(
                retake,
                totalCredits,
                MAX_CREDITS,
                maxDays,
                false,  // applyFirstPeriodFilter
                false,  // forceAddRetake (현재 사용 안 함)
                false   // ignoreDayLimit: 재수강도 요일 제한 지킴
        );

        // 현재까지 들어간 전공 개수
        int majorCountSoFar = countMajorsInTimetable(timetable);

        // 2순위: 전공 (최대 4개까지만 추가)
        int remainingMajorSlots = Math.max(0, MAX_MAJOR_COUNT - majorCountSoFar);
        if (remainingMajorSlots > 0) {
            List<Course> majorFiltered = major.stream()
                    .filter(c -> !usedCourseCodes.contains(nullToEmpty(c.getCourseCode())))
                    .collect(Collectors.toList());

            totalCredits = adder.addCoursesUpTo(
                    majorFiltered,
                    totalCredits,
                    MAX_CREDITS,
                    maxDays,
                    false,      // applyFirstPeriodFilter
                    false,            // forceAddRetake
                    false,            // ignoreDayLimit: 전공도 요일 제한 지킴
                    remainingMajorSlots
            );
        }

        // 3순위: 교양 (1차: 요일제한/1교시회피 반영)
        List<Course> liberalFiltered = liberal.stream()
                .filter(c -> !usedCourseCodes.contains(nullToEmpty(c.getCourseCode())))
                .collect(Collectors.toList());

        totalCredits = adder.addCourses(
                liberalFiltered,
                totalCredits,
                MAX_CREDITS,
                maxDays,
                avoidFirstPeriod,
                false,
                false
        );

        // 교양 2차: 아직 19학점 미만이고, "주 X일" 제한이 없을 때만 요일 제한 풀어서 시도
        if (totalCredits < MAX_CREDITS && maxDays == null) {
            List<Course> liberalSecond = liberal.stream()
                    .filter(c -> !usedCourseCodes.contains(nullToEmpty(c.getCourseCode())))
                    .collect(Collectors.toList());

            totalCredits = adder.addCourses(
                    liberalSecond,
                    totalCredits,
                    MAX_CREDITS,
                    null,             // maxDays 없음
                    avoidFirstPeriod, // 1교시 회피는 유지
                    false,
                    true              // ignoreDayLimit
            );
        }

        // MAX_CREDITS 초과 시 뒤에서 과목 잘라내기
        enforceMaxCredits(timetable);

        // 10) AiTimetable 기록 (유저당 플랜별 1개)
        AiTimetable aiTimetable = aiTimetableRepository
                .findByUser_IdAndPlanKey(user.getId(), planKey)
                .orElseGet(() -> AiTimetable.builder()
                        .user(user)
                        .timetable(timetable)
                        .planKey(planKey)
                        .build()
                );

        aiTimetable.setMessage(rawMessage);                     // 사용자가 남긴 자연어 원본
        aiTimetable.setPrompt(summary);                         // 파싱/요약용 문자열
        aiTimetable.update(buildResultSummary(timetable), timetable); // 결과 요약
        aiTimetableRepository.save(aiTimetable);

        return timetable;
    }

    /**
     * <사용자 학년에 맞는 강의 매칭 필터>
     * 사용자의 학년과 강의의 recommended_grade가 같은지 대조합니다.
     *
     * @author 김민호
     * @param {targetGrade}, {Course}
     * @return {true or false}
     */
    private boolean isMajorRecommendedGradeMatch(Integer targetGrade, Course c) {
        if (targetGrade == null) return true;  // 타겟 학년 정보 없으면 필터 안 함

        Integer rec = c.getRecommendedGrade(); // DB의 recommended_grade 컬럼 매핑
        if (rec == null) return false;         // 추천 학년이 비어 있으면 전공 후보에서 제외

        return rec.equals(targetGrade);
    }

    /**
     * <강의 전공 구분 필터>
     * 해당 강의가 전공인지 구분합니다.
     *
     * @author 김민호
     * @param {Course}
     * @return {전공|| 전필 || 전선}
     */
    private boolean isMajorCategory(Course c) {
        String cat = nullToEmpty(c.getCategory());
        return cat.startsWith("전") || cat.contains("전공") || cat.equals("전필") || cat.equals("전선")
                || cat.equalsIgnoreCase("major");
    }

    /**
     * <강의 교양 구분 필터>
     * 해당 강의가 교양인지 구분합니다.
     *
     * @author 김민호
     * @param {Course}
     * @return {교필 || 교선}
     */
    private boolean isLiberalCategory(Course c) {
        String cat = nullToEmpty(c.getCategory());
        return cat.equals("교필") || cat.equals("교선");
    }

    /**
     * <시간표 내 전공 갯수 카운터>
     * 시간표 내 전공이 몇개인지 셉니다
     *
     * @autor 김민호
     * @param {Timetable}
     * @return {cnt}
     */
    private int countMajorsInTimetable(Timetable t) {
        if (t.getItems() == null) return 0;
        int cnt = 0;
        for (TimetableItem it : t.getItems()) {
            String cat = nullToEmpty(it.getCategory());
            if (cat.startsWith("전") || cat.contains("전공") || cat.equals("전필") || cat.equals("전선")
                    || cat.equalsIgnoreCase("major")) cnt++;
        }
        return cnt;
    }

    /** 학과 엄격 매칭 헬퍼 함수
     *  강의의 학과와 유저의 학과가 일치 하는지 엄경하게 확인 합니다.
     *
     * @author 김민호
     * @param {userDept}, {courseDept}
     * @return {ture or false}
     */
    private boolean deptStrictMatch(String userDept, String courseDept) {
        if (userDept.isEmpty() || courseDept.isEmpty()) return false;
        return userDept.equals(courseDept);
    }

    /**
     * <시간표의 학과 선정 함수>
     * - 메시지에 학과명이 없으면 userDept 그대로 반환
     * - 메시지에 여러 학과가 있으면, 첫 번째로 매칭된 학과를 사용
     *
     * @author 김민호
     * @param {summary}, {userDeptNorm}, {List all}
     * @return {dept}
     */
    private String resolveTargetDepartment(String summary, String userDeptNorm, List<Course> all) {
        String msgNorm = normDept(summary);
        if (msgNorm.isEmpty()) {
            return userDeptNorm;
        }

        String chosen = userDeptNorm;
        for (Course c : all) {
            String originalDept = nullToEmpty(c.getDepartment());
            if (originalDept.isEmpty()) continue;

            String courseDeptNorm = normDept(originalDept);
            if (courseDeptNorm.isEmpty()) continue;

            // 유저 학과와 동일하면 override 할 필요 없음
            if (courseDeptNorm.equals(userDeptNorm)) continue;

            // 메시지에 다른 학과명이 포함되어 있으면 그 학과를 사용
            if (msgNorm.contains(courseDeptNorm)) {
                chosen = courseDeptNorm;
                break; // 첫 매칭 학과 사용
            }
        }

        return chosen;
    }

    /**
     * <채팅에서 학년 선정 서비스>
     * 채팅 내용에서 "3학년", "4학년" 같은 표현을 찾아 타겟 학년 반환.
     * 없으면 userGrade 그대로 씀.
     *
     * @author 김민호
     * @param {summary}, {userGrade}
     * @return {userGrade}
     */
    private Integer resolveTargetGrade(String summary, Integer userGrade) {
        String text = nullToEmpty(summary).replaceAll("\\s+", "");
        Matcher m = Pattern.compile("(\\d)학년").matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return userGrade;
    }

    /**
     * <사용자가 원하는 공강 추출 함수>
     * "금요일 공강", "수요일 빼고", "월요일은 쉬고 싶어요" 같은 표현에서 공강 요일 추출
     *
     * @author 김민호
     * @param {msg}
     * @return  {result}
     */
    private static Set<String> parseOffDays(String msg) {
        String text = nullToEmpty(msg).replaceAll("\\s+", "");

        Set<String> result = new HashSet<>();

        Map<String, String> dayMap = new HashMap<>();
        dayMap.put("월", "MON");
        dayMap.put("화", "TUE");
        dayMap.put("수", "WED");
        dayMap.put("목", "THU");
        dayMap.put("금", "FRI");
        dayMap.put("토", "SAT");
        dayMap.put("일", "SUN");

        Pattern p = Pattern.compile("(월|화|수|목|금|토|일)요일?(공강|빼고|제외|쉬고싶|쉬고싶어요|수업없|없었으면)");
        Matcher m = p.matcher(text);

        while (m.find()) {
            String kor = m.group(1);
            String code = dayMap.get(kor);
            if (code != null) {
                result.add(code);
            }
        }

        return result;
    }

    private static String buildTitle(User user, AiTimetableRequest req) {
        String dep = nullToEmpty(user.getDepartment());
        Integer g = user.getGrade();
        if (dep.isEmpty() && g == null) {
            return (req.getYear() != null && req.getSemester() != null)
                    ? String.format("%d-%d학기 AI 생성 시간표", req.getYear(), req.getSemester())
                    : "AI 생성 시간표";
        }
        return String.format("%s의 %s%s 시간표",
                nullToEmpty(user.getName()),
                g != null ? (g + "학년 ") : "",
                dep.isEmpty() ? "" : dep
        ).trim();
    }

    /**
     * <시간표 내 강의 학점 총합이 19학점이 안넘도록 도와주는 함수>
     * 시간표 내 학점 총합이 19학점 안넘도록
     *
     * @author 김민호
     * @param {timetable}
     * @return
     */
    private void enforceMaxCredits(Timetable timetable) {
        if (timetable.getItems() == null || timetable.getItems().isEmpty()) {
            return;
        }

        List<TimetableItem> items = new ArrayList<>(timetable.getItems());

        int sum = 0;
        for (TimetableItem it : items) {
            if (it.getCourse() != null && it.getCourse().getCredit() != null) {
                sum += it.getCourse().getCredit();
            }
        }

        if (sum <= MAX_CREDITS) {
            return;
        }

        // 뒤에서부터 하나씩 삭제 (보통 나중에 들어간 교양 과목부터 빠짐)
        ListIterator<TimetableItem> it = items.listIterator(items.size());
        while (sum > MAX_CREDITS && it.hasPrevious()) {
            TimetableItem last = it.previous();

            Integer credit = (last.getCourse() != null) ? last.getCourse().getCredit() : null;
            int c = (credit != null) ? credit : 0;
            sum -= c;

            // DB/엔티티 둘 다에서 제거
            timetableItemRepository.delete(last);
            timetable.getItems().remove(last);
        }
    }

    /**
     * <결과 요약 함수>
     * 결과 요약을 반환해용
     *
     * @author 김민호
     * @param {timetable}
     * @return  {majorCount}, {credit}....
     */
    private static String buildResultSummary(Timetable timetable) {
        if (timetable.getItems() == null || timetable.getItems().isEmpty()) {
            return "전공 0개(최대 " + MAX_MAJOR_COUNT + "), 교양 0개, 총 0학점, 주 0일";
        }

        int credits = timetable.getItems().stream()
                .mapToInt(i -> i.getCourse() != null ? i.getCourse().getCredit() : 0)
                .sum();

        long majorCnt = timetable.getItems().stream().filter(i -> {
            String cat = nullToEmpty(i.getCategory());
            return cat.startsWith("전") || cat.contains("전공") || cat.equals("전필") || cat.equals("전선")
                    || cat.equalsIgnoreCase("major");
        }).count();

        long liberalCnt = timetable.getItems().stream().filter(i -> {
            String cat = nullToEmpty(i.getCategory());
            return cat.equals("교필") || cat.equals("교선");
        }).count();

        // 사용된 요일 수집
        Set<String> daySet = new HashSet<>();
        for (TimetableItem it : timetable.getItems()) {
            daySet.add(nullToEmpty(it.getDayOfWeek()));
        }

        int dayCount = (int) daySet.stream()
                .filter(d -> !d.isBlank())
                .count();

        List<String> orderedDays = Arrays.asList("MON", "TUE", "WED", "THU", "FRI", "SAT");
        List<String> usedDaysKorean = orderedDays.stream()
                .filter(daySet::contains)
                .map(AiTimetableService::toKoreanDay)
                .collect(Collectors.toList());

        String dayPart = usedDaysKorean.isEmpty()
                ? "주 0일"
                : "주 " + dayCount + "일 (" + String.join(", ", usedDaysKorean) + ")";

        return String.format("전공 %d개(최대 %d), 교양 %d개, 총 %d학점, %s",
                majorCnt, MAX_MAJOR_COUNT, liberalCnt, credits, dayPart);
    }
    /**
     * <요일 한국어 변환>
     * 디비에 영어로 된 요일을 한국어로 변환 해줍니다.
     *
     * @author 김민호
     * @param {day}
     * @return  {요일}
     */
    private static String toKoreanDay(String day) {
        switch (day) {
            case "MON": return "월";
            case "TUE": return "화";
            case "WED": return "수";
            case "THU": return "목";
            case "FRI": return "금";
            case "SAT": return "토";
            case "SUN": return "일";
            default:    return day;
        }
    }

    private static Comparator<Course> byStartPeriodWithAvoid(boolean avoidFirstPeriod) {
        return (a, b) -> {
            int sa = safeInt(a.getStartPeriod());
            int sb = safeInt(b.getStartPeriod());
            if (avoidFirstPeriod) {
                if (sa == 1 && sb != 1) return 1;
                if (sb == 1 && sa != 1) return -1;
            }
            return Integer.compare(sa, sb);
        };
    }

    /** 재수강 후보 탐색: 메시지에 이름/코드가 등장하면 픽업 */
    private static List<Course> pickRetakeCourses(String message, List<Course> all) {
        String msg = normalize(message);
        boolean cue = msg.contains("재수강") || msg.contains("재수")
                || msg.contains("retake") || msg.contains("repeat")
                || msg.contains("꼭들") || msg.contains("반드시") || msg.contains("다시들");
        if (!cue) return new ArrayList<>();

        List<Course> list = new ArrayList<>();
        for (Course c : all) {
            String name = normalize(c.getName());
            String code = normalize(c.getCourseCode());
            if ((!name.isEmpty() && containsToken(msg, name))
                    || (!code.isEmpty() && msg.contains(code))) {
                list.add(c);
            }
        }
        return list;
    }

    private String normalizePlanKey(String planKey) {
        if (planKey == null || planKey.isBlank()) return "A";
        String upper = planKey.trim().toUpperCase();
        if (!upper.equals("A") && !upper.equals("B") && !upper.equals("C")) return "A";
        return upper;
    }

    private static boolean containsToken(String hay, String needle) {
        if (needle.length() < 3) return false;
        return hay.contains(needle);
    }

    private static String normDept(String s) {
        s = nullToEmpty(s);
        s = s.replaceAll("\\s+", "");
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int safeInt(Integer i) {
        return i == null ? 0 : i;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        return s.toLowerCase().replaceAll("\\s+", "");
    }

    // "주 3일", "주 3회", "일주일에 3번", "3일만 학교" 등 여러 표현을 커버
    private static Optional<Integer> parseMaxDays(String msg) {
        String text = nullToEmpty(msg);

        // 1) "주 3일"
        Matcher m1 = Pattern.compile("주\\s*(\\d)\\s*일").matcher(text);
        if (m1.find()) {
            try {
                return Optional.of(Integer.parseInt(m1.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        // 2) "주 3회", "주 3번"
        Matcher m2 = Pattern.compile("주\\s*(\\d)\\s*(회|번)").matcher(text);
        if (m2.find()) {
            try {
                return Optional.of(Integer.parseInt(m2.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        // 3) "일주일에 3번", "일주일에 3일"
        Matcher m3 = Pattern.compile("일주일에\\s*(\\d)\\s*(번|일|회)").matcher(text);
        if (m3.find()) {
            try {
                return Optional.of(Integer.parseInt(m3.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        // 4) "3일만 학교", "3번만 학교"
        Matcher m4 = Pattern.compile("(\\d)\\s*(일|번|회)만\\s*학교").matcher(text);
        if (m4.find()) {
            try {
                return Optional.of(Integer.parseInt(m4.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        return Optional.empty();
    }

    private static boolean detectAvoidFirstPeriod(String msg) {
        String m = nullToEmpty(msg).replaceAll("\\s+", "");
        return (m.contains("1교시") || m.contains("첫교시"))
                && (m.contains("피") || m.contains("빼") || m.contains("안") || m.contains("없"));
    }

    /**
     * userId 기준으로 AI 시간표 생성에 사용된 요약 메세지(prompt)를 반환
     * - 여러 플랜 중 createdAt 최신 1개 기준
     */
    @Transactional(readOnly = true)
    public TimetableSummaryResponse getTimetableSummary(Long userId) {
        List<AiTimetable> list = aiTimetableRepository.findAllByUser_Id(userId);

        if (list.isEmpty()) {
            return TimetableSummaryResponse.builder()
                    .userId(userId)
                    .summary("")
                    .build();
        }

        AiTimetable latest = list.stream()
                .filter(it -> it.getCreatedAt() != null)
                .max(Comparator.comparing(AiTimetable::getCreatedAt))
                .orElse(list.get(0));

        String prompt = latest.getPrompt();
        if (prompt == null) prompt = "";

        return TimetableSummaryResponse.builder()
                .userId(userId)
                .summary(prompt)
                .build();
    }

    // =======================
    // AI 시간표 메타 저장/조회/삭제
    // =======================

    /** 수동 저장/수정 (플랜별) */
    @Transactional
    public AiTimetableResponse saveAiTimetable(AiTimetableSaveRequest request) {
        if (request.getUserId() == null) throw new IllegalArgumentException("userId는 필수입니다.");
        if (request.getTimetableId() == null) throw new IllegalArgumentException("timetableId는 필수입니다.");

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 유저입니다."));
        Timetable timetable = timetableRepository.findById(request.getTimetableId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 시간표입니다."));

        String planKey = normalizePlanKey(request.getPlanKey());

        AiTimetable aiTimetable = aiTimetableRepository
                .findByUser_IdAndPlanKey(user.getId(), planKey)
                .orElseGet(() -> AiTimetable.builder()
                        .user(user)
                        .timetable(timetable)
                        .planKey(planKey)
                        .build()
                );

        // 여기서는 resultSummary만 수정 (prompt는 그대로 유지)
        aiTimetable.update(request.getResultSummary(), timetable);
        return AiTimetableResponse.from(aiTimetableRepository.save(aiTimetable));
    }

    /**
     * <AI 시간표 조회 서비스(Plan A,B,C)>
     * AI가 만든 시간표를 조회 합니다.
     *
     * @author 김민호
     * @param {userId}, {planKey}
     * @return  {AiTimetableResponse}
     */
    @Transactional(readOnly = true)
    public AiTimetableResponse getAiTimetable(Long userId, String planKey) {
        String pk = normalizePlanKey(planKey);
        AiTimetable entity = aiTimetableRepository.findByUser_IdAndPlanKey(userId, pk)
                .orElseThrow(() -> new IllegalArgumentException("해당 유저의 AI 시간표가 없습니다. plan=" + pk));
        return AiTimetableResponse.from(entity);
    }

    /**
     * <AI 시간표 조회 서비스>
     * AI가 만든 시간표를 조회 합니다.
     *
     * @author 김민호
     * @param {userId}, {planKey}
     * @return  {AiTimetableResponse}
     */
    @Transactional(readOnly = true)
    public List<AiTimetableResponse> getAiTimetables(Long userId) {
        List<AiTimetable> list = aiTimetableRepository.findAllByUser_Id(userId);
        return list.stream()
                .map(AiTimetableResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * <AI 시간표 삭제 서비스>
     * AI가 만든 시간표를 삭제 합니다.
     *
     * @author 김민호
     * @param {userId}, {planKey}
     * @return
     */
    @Transactional
    public void deleteAiTimetable(Long userId, String planKey) {
        if (planKey == null || planKey.isBlank()) {
            aiTimetableRepository.deleteByUser_Id(userId);
        } else {
            String pk = normalizePlanKey(planKey);
            aiTimetableRepository.deleteByUser_IdAndPlanKey(userId, pk);
        }
    }

    // 과목 추가 헬퍼 (내부 클래스)
    private static class CourseAdder {
        private final Timetable timetable;
        private final TimetableItemRepository repo;
        private final Set<String> usedCodes;
        // dayOfWeek -> [startPeriod, endPeriodExclusive) 리스트
        private final Map<String, List<int[]>> occupied;
        private final Set<String> usedDays;
        private final Set<String> usedNames; // 과목 이름(정규화) 중복 체크용

        private CourseAdder(Timetable timetable,
                            TimetableItemRepository repo,
                            Set<String> usedCodes,
                            Map<String, List<int[]>> occupied,
                            Set<String> usedDays,
                            Set<String> usedNames) {
            this.timetable = timetable;
            this.repo = repo;
            this.usedCodes = usedCodes;
            this.occupied = occupied;
            this.usedDays = usedDays;
            this.usedNames = usedNames;
        }

        int addCourses(List<Course> candidates,
                       int currentCredits,
                       int maxCredits,
                       Integer maxDays,
                       boolean applyFirstPeriodFilter,
                       boolean forceAddRetake,
                       boolean ignoreDayLimit) {

            for (Course c : candidates) {
                if (currentCredits >= maxCredits) break;
                if (!canAdd(c, currentCredits, maxCredits, maxDays,
                        applyFirstPeriodFilter, forceAddRetake, ignoreDayLimit)) {
                    continue;
                }
                currentCredits = add(c, currentCredits);
            }
            return currentCredits;
        }

        int addCoursesUpTo(List<Course> candidates,
                           int currentCredits,
                           int maxCredits,
                           Integer maxDays,
                           boolean applyFirstPeriodFilter,
                           boolean forceAddRetake,
                           boolean ignoreDayLimit,
                           int maxToAdd) {
            int added = 0;
            for (Course c : candidates) {
                if (currentCredits >= maxCredits) break;
                if (added >= maxToAdd) break;
                if (!canAdd(c, currentCredits, maxCredits, maxDays,
                        applyFirstPeriodFilter, forceAddRetake, ignoreDayLimit)) {
                    continue;
                }
                currentCredits = add(c, currentCredits);
                added++;
            }
            return currentCredits;
        }

        private boolean canAdd(Course c,
                               int currentCredits,
                               int maxCredits,
                               Integer maxDays,
                               boolean applyFirstPeriodFilter,
                               boolean forceAddRetake,
                               boolean ignoreDayLimit) {

            String code = nullToEmpty(c.getCourseCode());
            if (!code.isEmpty() && usedCodes.contains(code)) return false;

            String nameKey = normalize(c.getName());
            if (!nameKey.isEmpty() && usedNames.contains(nameKey)) return false;

            String day = nullToEmpty(c.getDayOfWeek());
            Integer startP = c.getStartPeriod();
            Integer endP   = c.getEndPeriod();
            if (day.isEmpty() || startP == null || endP == null) {
                return false;
            }

            if (applyFirstPeriodFilter && startP == 1) return false;

            int start = safeInt(startP);
            int endExclusive = safeInt(endP) + 1;

            // 요일 제한
            if (!ignoreDayLimit && maxDays != null) {
                boolean newDay = !usedDays.contains(day);
                if (newDay && (usedDays.size() + 1) > maxDays) return false;
            }

            // 교시 구간만으로 겹침 체크
            if (isConflict(day, start, endExclusive)) return false;

            int after = currentCredits + safeInt(c.getCredit());
            if (after > maxCredits) return false;

            return true;
        }

        private int add(Course c, int currentCredits) {
            TimetableItem item = TimetableItem.builder()
                    .timetable(timetable)
                    .course(c)
                    .courseName(c.getName())
                    .dayOfWeek(c.getDayOfWeek())
                    .startPeriod(c.getStartPeriod())
                    .endPeriod(c.getEndPeriod())
                    .room(c.getRoom())
                    .category(c.getCategory())
                    .build();

            try {
                // 먼저 addItem으로 충돌 검사
                timetable.addItem(item);
            } catch (IllegalStateException e) {
                // 충돌났으면 아예 추가하지 않음 (DB에도 저장 안 됨)
                return currentCredits;
            }

            repo.save(item); // 검증 통과한 것만 저장

            String code = nullToEmpty(c.getCourseCode());
            if (!code.isEmpty()) usedCodes.add(code);

            String nameKey = normalize(c.getName());
            if (!nameKey.isEmpty()) usedNames.add(nameKey);

            occupy(
                    nullToEmpty(c.getDayOfWeek()),
                    safeInt(c.getStartPeriod()),
                    safeInt(c.getEndPeriod()) + 1
            );

            return currentCredits + safeInt(c.getCredit());
        }

        private boolean isConflict(String day, int start, int endExclusive) {
            if (day.isEmpty()) return false;
            List<int[]> list = occupied.getOrDefault(day, new ArrayList<>());
            for (int[] r : list) {
                int as = r[0], ae = r[1];
                if (start < ae && as < endExclusive) return true;
            }
            return false;
        }

        private void occupy(String day, int start, int endExclusive) {
            if (day.isEmpty()) return;
            usedDays.add(day);
            occupied.computeIfAbsent(day, k -> new ArrayList<>())
                    .add(new int[]{start, endExclusive});
        }
    }

    // ===== 교시 → 시간 단위 변환 (현재는 안 쓰지만 혹시 몰라 보존) =====
    private static int periodStartMinutes(int period) {
        switch (period) {
            case 1:  return 9 * 60;
            case 2:  return 10 * 60;
            case 3:  return 11 * 60;
            case 4:  return 12 * 60;
            case 5:  return 13 * 60;
            case 6:  return 14 * 60;
            case 7:  return 15 * 60;
            case 8:  return 16 * 60;
            case 9:  return 17 * 60;

            case 21: return 9 * 60;
            case 22: return 10 * 60 + 30;
            case 23: return 12 * 60;
            case 24: return 13 * 60 + 30;
            case 25: return 15 * 60;
            case 26: return 16 * 60 + 30;
            default:
                throw new IllegalArgumentException("알 수 없는 교시: " + period);
        }
    }

    private static int periodEndMinutes(int period) {
        switch (period) {
            case 1:  return 9 * 60 + 50;
            case 2:  return 10 * 60 + 50;
            case 3:  return 11 * 60 + 50;
            case 4:  return 12 * 60 + 50;
            case 5:  return 13 * 60 + 50;
            case 6:  return 14 * 60 + 50;
            case 7:  return 15 * 60 + 50;
            case 8:  return 16 * 60 + 50;
            case 9:  return 17 * 60 + 50;

            case 21: return 10 * 60 + 15;
            case 22: return 11 * 60 + 45;
            case 23: return 13 * 60 + 15;
            case 24: return 14 * 60 + 45;
            case 25: return 16 * 60 + 15;
            case 26: return 17 * 60 + 45;
            default:
                throw new IllegalArgumentException("알 수 없는 교시: " + period);
        }
    }
}