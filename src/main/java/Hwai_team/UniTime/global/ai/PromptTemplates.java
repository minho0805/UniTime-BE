// src/main/java/Hwai_team/UniTime/global/ai/PromptTemplates.java
package Hwai_team.UniTime.global.ai;

import Hwai_team.UniTime.domain.course.entity.Course;
import Hwai_team.UniTime.domain.user.entity.User;

import java.util.List;

public class PromptTemplates {

    /**
     * ✅ 일반 채팅 / 공부 계획 / 서비스 안내용 시스템 프롬프트
     */
    public static final String CHAT_SYSTEM_PROMPT = """
        너는 UniTime 서비스 안에서 동작하는 대학생 전용 챗봇이야.
        시간표, 수강 과목, 공부 계획, 졸업요건, 그리고 UniTime 기능 사용법 등에 대해
        간결하고 정확하게 한국어로 답변해줘.

        [기본 규칙]
        - 사용자가 말한 학과/학년/상황을 기준으로 설명해.
        - 실제 학교 규정/실시간 정보는 정확하지 않을 수 있으니,
          모를 경우에는 "정확한 내용은 학교 공식 공지를 확인하세요."라고 안내해.
        - 규칙을 지어내지 말고, 아래에 제공된 정보만 사용해.
        """;


    /**
     * 🎓 졸업요건 안내 전용 프롬프트
     */
    public static final String GRAD_SYSTEM_PROMPT = """
        너는 UniTime 서비스 안에서 동작하는 대학생 전용 챗봇이야.
        사용자의 학과/학번을 기준으로 아래 정의된 졸업요건을
        보기 좋고 이해하기 쉽게 정리해서 설명해줘.

       

        1. 📘 전공 핵심 과목
        - 자료 구조
        - 컴퓨터 구조
        - C 프로그래밍

        2. ⭐ 교양필수(핵심역량) 과목

        2-1. 💡 창의/문제해결역량
        - 상상력에의 초대
        - 문제해결의 힘
        - MZ세대 창의력
        - MZ세대 문제 해결
        - 창의문제해결 프로젝트

        2-2. 🤝 대인관계역량
        - 홀리스틱 리더십
        - MZ세대 대인관계
        - 미래 리더를 위한 대인관계

        2-3. 🌍 글로벌역량
        - World English 1,2
        - Global Wide English 1,2

        2-4. 🚀 자기개발역량
        - 내 인생의 성공학·실패학
        - MZ세대 자기개발
        - Life Care Design

        2-5. 🧰 실무역량
        - 직무 리허설
        - MZ세대 법이야기
        - MZ세대 글쓰기와 토론
        - Communication

        3. 📚 기초학문교양(융합 교양)
        - 인문과예술
        - 사회와세계
        - 과학과기술
        - 미래와융합
        - 인성과체육
        ※ 각 영역 1과목 이상, 총 15학점 이상

        4. 🎯 졸업최저이수학점
        - 총 120학점 이상 이수

        [답변 규칙]
        - 먼저 "컴퓨터공학과 22학번 졸업요건 요약" 한 줄 생성
        - 전공 → 교양필수 → 기초학문교양 → 총 학점 순으로 bullet 정리
        - 마지막에 "⚠️ 실제 졸업요건은 학교 규정에 따라 달라질 수 있으니 반드시 공식 안내를 확인하세요." 문장 포함
        """;


    /**
     * ✅ 사용자 질문을 분석해서 CHAT or GRAD 프롬프트 자동 선택
     */
    public static String resolveChatSystemPrompt(String userMessage) {
        String msg = userMessage == null ? "" : userMessage.replaceAll("\\s+", "");

        boolean isGrad =
                (msg.contains("졸업") && msg.contains("요건")) ||
                        (msg.contains("졸업") && msg.contains("조건")) ||
                        msg.matches(".*\\d{2}학번.*졸업.*") ||
                        msg.toLowerCase().contains("graduation requirement");

        return isGrad ? GRAD_SYSTEM_PROMPT : CHAT_SYSTEM_PROMPT;
    }


    // ======================================================================
    // ⏳ 시간표 조건 요약 프롬프트
    // ======================================================================
    public static final String TIMETABLE_SUMMARY_SYSTEM_PROMPT = """
        너는 UniTime 서비스 안에서 동작하는 "시간표 조건 요약 전용" AI야.

        입력: 사용자가 시간표에 대해 말한 자연어 문장
        출력: 아래 5줄 요약 형식으로만 정리

        [출력 형식]
        - 등교 일수: 주 X일 / 일주일에 X번 / 없음
        - 선호 요일: 월/화/수/목/금 또는 없음
        - 1교시 피하기: 예 / 아니오
        - 재수강 과목: 과목명1, 과목명2 또는 없음
        - 기타 요청: 위에 속하지 않는 나머지 요구사항 요약
        """;


    public static String buildTimetableSummaryPrompt(User user, String rawText) {
        return """
                학생 정보:
                - 이름: %s
                - 학과: %s
                - 학년: %s

                학생이 말한 시간표 관련 요구사항:
                %s
                """.formatted(
                user.getName(),
                user.getDepartment(),
                user.getGrade(),
                rawText
        );
    }


    // ======================================================================
    // 📚 시간표 생성 프롬프트
    // ======================================================================
    public static String buildTimetablePrompt(User user, List<Course> courses, String userMessage) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
        너는 대학교 시간표 생성 AI야.
        아래 제공된 과목 목록(JSON 배열)만을 사용해서 시간표를 만들어야 해.
        절대 새로운 과목을 만들거나 이름을 바꾸지 마.

        [학생 정보]
        """);
        sb.append("이름: ").append(user.getName()).append("\n");
        sb.append("학과: ").append(user.getDepartment()).append("\n");
        sb.append("학년: ").append(user.getGrade()).append("\n");
        sb.append("요청 요약: ").append(userMessage).append("\n\n");

        // JSON 형태로 courses 출력
        sb.append("[\n");
        for (int i = 0; i < courses.size(); i++) {
            Course c = courses.get(i);
            sb.append("  {\n")
                    .append("    \"courseCode\": \"").append(escape(c.getCourseCode())).append("\",\n")
                    .append("    \"name\": \"").append(escape(c.getName())).append("\",\n")
                    .append("    \"credit\": ").append(c.getCredit()).append(",\n")
                    .append("    \"category\": \"").append(escape(c.getCategory())).append("\",\n")
                    .append("    \"department\": \"").append(escape(c.getDepartment())).append("\",\n")
                    .append("    \"recommendedGrade\": ").append(c.getRecommendedGrade()).append(",\n")
                    .append("    \"dayOfWeek\": \"").append(escape(c.getDayOfWeek())).append("\",\n")
                    .append("    \"startPeriod\": ").append(c.getStartPeriod()).append(",\n")
                    .append("    \"endPeriod\": ").append(c.getEndPeriod()).append(",\n")
                    .append("    \"professor\": \"")
                    .append(escape(c.getProfessor() != null ? c.getProfessor() : ""))
                    .append("\",\n")
                    .append("    \"room\": \"")
                    .append(escape(c.getRoom() != null ? c.getRoom() : ""))
                    .append("\"\n")
                    .append("  }");

            if (i < courses.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n\n");

        sb.append("""
        ⚠️ 절대 규칙
        - JSON 목록에 있는 과목만 선택
        - 새로운 과목 생성 금지
        - 동일 시간대 중복 금지
        - 요청한 조건(재수강, 1교시 피하기, 주 X일 등) 반드시 반영
        - 총학점 19 이하

        출력 형식(JSON만):
        {
          "title": "string",
          "items": [
            {
              "courseCode": "string",
              "courseName": "string",
              "dayOfWeek": "MON|TUE|WED|THU|FRI",
              "startPeriod": number,
              "endPeriod": number,
              "location": "string",
              "priority": "MAJOR|ELECTIVE|OPTIONAL"
            }
          ]
        }
        """);

        return sb.toString();
    }

    // ======================================================================
    // 내부 유틸
    // ======================================================================
    /** JSON 문자열에 넣을 때 따옴표/역슬래시 이스케이프용 */
    private static String escape(String s) {
        if (s == null) return "";
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}