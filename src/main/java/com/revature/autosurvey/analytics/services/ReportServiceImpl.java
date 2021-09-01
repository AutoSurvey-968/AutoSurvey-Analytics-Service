package com.revature.autosurvey.analytics.services;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.revature.autosurvey.analytics.beans.AnalyticsData;
import com.revature.autosurvey.analytics.beans.Question;
import com.revature.autosurvey.analytics.beans.Question.QuestionType;
import com.revature.autosurvey.analytics.beans.Report;
import com.revature.autosurvey.analytics.beans.Response;
import com.revature.autosurvey.analytics.beans.Survey;
import com.revature.autosurvey.analytics.data.ResponseDao;
import com.revature.autosurvey.analytics.data.SurveyDao;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ReportServiceImpl implements ReportService {
	
	@Autowired
	private ResponseDao responseDao;
	@Autowired
	public void setResponseDao(ResponseDao responseDao) {
		this.responseDao=responseDao;
	}
	
	@Autowired
	private SurveyDao surveyDao;
	@Autowired
	public void setSurveyDao(SurveyDao surveyDao) {
		this.surveyDao=surveyDao;
	}
	
	private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	@Override
	public Mono<Report> getReport(String surveyId) {
		Mono<Survey> survey = surveyDao.getSurvey(surveyId);
		Flux<Response> responses = responseDao.getResponses(surveyId);
		return createReport(survey,responses);
	}
	


	@Override
	public Mono<Report> getReport(String surveyId, String weekDay, String batchName) {
		
		Mono<Survey> survey = surveyDao.getSurvey(surveyId);
		Flux<Response> responses = responseDao.getResponses(surveyId, weekDay, batchName);
		
		LocalDate ld = LocalDate.parse(weekDay, DATE_TIME_FORMAT);
		Flux<Response> oldResponses = responseDao.getResponses(surveyId, ld.minusDays(7).format(DATE_TIME_FORMAT), batchName);
		Mono<Report> oldReport = createReport(survey, oldResponses);
		Mono<Report> newReport = createReport(survey, responses);
		return addDeltaToReport(oldReport, newReport);
	}
	
	@Override
	public Mono<Report> getReport(String surveyId, String weekDay) {
		
		Mono<Survey> survey = surveyDao.getSurvey(surveyId);
		Flux<Response> responses = responseDao.getResponses(surveyId, weekDay);	

		LocalDate ld = LocalDate.parse(weekDay, DATE_TIME_FORMAT);
		Flux<Response> oldResponses = responseDao.getResponses(surveyId, ld.minusDays(7).format(DATE_TIME_FORMAT));
		Mono<Report> oldReport = createReport(survey, oldResponses);
		Mono<Report> newReport = createReport(survey, responses);
		return addDeltaToReport(oldReport, newReport);
	}

	private Mono<Report> addDeltaToReport(Mono<Report> oldReport, Mono<Report> newReport) {
		return newReport.flatMap(report -> 
			oldReport.map(old -> {
				if(old.getAverages().size() == 0 && old.getPercentages().size() == 0) {
					return report;
				}
				Map<String, AnalyticsData> averages = report.getAverages();
				for(Entry<String, AnalyticsData> question : averages.entrySet()) {
					AnalyticsData newData = question.getValue();
					AnalyticsData oldData = old.getAverages().get(question.getKey());
					newData.setDelta(newData.getDatum()-oldData.getDatum());
				}
				Map<String, Map<String, AnalyticsData>> percentages = report.getPercentages();
				for(Entry<String, Map<String, AnalyticsData>> question : percentages.entrySet()) {
					Map<String, AnalyticsData> sub = question.getValue();
					for(Entry<String, AnalyticsData> option : sub.entrySet()) {
						AnalyticsData newData = option.getValue();
						AnalyticsData oldData = old.getPercentages().get(question.getKey()).get(option.getKey());
						newData.setDelta(newData.getDatum()-oldData.getDatum());
					}
				}
				return report;
			})
		);
	}

	private Mono<Report> createReport(Mono<Survey> survey, Flux<Response> responses) {

		/*
		 * flatMap contents of survey to be used with a map of the contents of responses.
		 * The map of the list of responses' content will return a Mono of Report that's been constructed with populated fields to the flatMap,
		 * flatMap will return the Mono of the previous map.
		 */
		return survey.flatMap(s -> {
			Mono<List<Response>> toMap = responses.collectList();
			return toMap.map(r -> {
				Report report = new Report(s.getUuid().toString()); // Testing to see if we get the UUID from Survey, will probably change type of Report.getSurveyId to UUID later
				report.setAverages(new HashMap<>());
				report.setPercentages(new HashMap<>());
				if(r.isEmpty()) {
					return report;
				}
				s.getQuestions().forEach(question -> {

					if(question == null) {
						return;
					}
					//currently using short answer because number doesn't exist
					if(question.getQuestionType() == QuestionType.RADIO) {
						AnalyticsData d = average(question, r);
						if(d==null) {
							return;
						}
						report.getAverages().put(question.getTitle(), d);
					}
					if(question.getQuestionType() == QuestionType.DROPDOWN) {
						report.getPercentages().put(question.getTitle(), percentages(question, r));
					}
				});

				return report;
			});
		});
	}
	
	private AnalyticsData average(Question question, List<Response> r) {
		Double average = 0.0;
		AnalyticsData d = new AnalyticsData();
		if(question.getTitle() == null) {
			return null;
		}
		int size= r.size();

		//if processible make an average and add to report
		for(int i = 0; i < r.size(); i++) {
			Response res = r.get(i);
			if(res.getResponses() == null) {
				size--;
				continue;
			}
			if(res.getResponses().get(question.getTitle())!=null&&!res.getResponses().get(question.getTitle()).equals("")){
				String s = r.get(i).getResponses().get(question.getTitle());
				try {
					average += Double.valueOf(s);
				} catch (NumberFormatException e) {
					size--;
				}

			}else {
				size--;
			}
		}
		if(size==0) {
			return null;
		}
		d.setDatum(average/size);
		return d;
	}

	private Map<String, AnalyticsData> percentages(Question question, List<Response> r){
		Map<String, AnalyticsData> choicesMap = new HashMap<>();
		int total = 0;
		question.getChoices().forEach(choice -> {

			AnalyticsData d = new AnalyticsData();
			d.setDatum(0.0);
			choicesMap.put(choice, d);
		}

		);
		//adding up choices
		for(int i = 0; i < r.size(); i++) {
			if(r.get(i) == null || r.get(i).getResponses() == null || question.getTitle() == null) {
				continue;
			}
			String questionTitle = r.get(i).getResponses().get(question.getTitle());
			if(choicesMap.keySet().contains(questionTitle)) {
				double value = choicesMap.get(questionTitle).getDatum();
				AnalyticsData d = new AnalyticsData();
				d.setDatum(++value);
				choicesMap.put(questionTitle, d);
				total++;
			}
			
		}//creating percentages
        for (Map.Entry<String, AnalyticsData> choiceEntry : choicesMap.entrySet()) {
			AnalyticsData result = choiceEntry.getValue();
			if(total!=0) {
				result.setDatum(result.getDatum()/total);
				choicesMap.put(choiceEntry.getKey(), result);
			}
        }
        return choicesMap;
	}
	
}
