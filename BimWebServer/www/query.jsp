<%@page import="org.bimserver.interfaces.objects.SQueryEngine"%>
<%@page import="org.bimserver.models.store.QueryEngine"%>
<%@ page contentType="text/html; charset=UTF-8" %>
<%@page import="java.util.Collections"%>
<%@page import="java.util.List"%>
<%@page import="org.bimserver.interfaces.objects.SUserType"%>
<%@page import="org.bimserver.interfaces.objects.SSerializer"%>
<jsp:useBean id="loginManager" scope="session" class="org.bimserver.web.LoginManager" />
<%
	long roid = Long.parseLong(request.getParameter("roid"));
	List<String> classes = loginManager.getService().getAvailableClasses();
	Collections.sort(classes);
%>
<div class="tabber" id="querytab">
<div class="tabbertab" title="Simple" id="simple">
<table>
<tr class="downloadframe">
	<td width="120">Object ID</td>
	<td width="320">
		<input type="text" name="oids" id="oids"/>
	</td>
	<td>
		<a href="#" id="queryoidsbutton" value="">Download</a>
	</td>
	<td>
		<input type="hidden" name="roid" value="<%=roid %>"/>
	</td>
	<td>
		<div class="downloadResult" style="display: none"></div>
	</td>
</tr>
</table>

<table>
<tr class="downloadframe">
	<td width="120">Globally Unique ID</td>
	<td width="320">
		<input type="text" name="guids" id="guids"/>
	</td>
	<td>
		<a href="#" id="queryguidsbutton">Download</a>
	</td>
	<td> 
		<input type="hidden" name="roid" value="<%=roid %>"/>
	</td>
	<td>
		<div class="downloadResult" style="display: none"></div>
	</td>
</tr>
</table>

<!--  <form action="<%=request.getContextPath() %>/download" method="get"> -->
<table>
<tr class="downloadframe">
	<td width="120">IFC Class</td>
	<td width="320">
		<select name="class" id="cid">
<%
	for (String className : classes) {
%>
		<option value="<%=className%>"><%=className %></option>
<%
	}
%>
		</select>
	</td>
	<td>
		<a href="#" id="queryclassesbutton">Download</a>
	</td>
	<td>
		<input type="hidden" name="roid" value="<%=roid %>"/>
	</td>
	<td>
		<div class="downloadResult" style="display: none"></div>
	</td>
</tr>
</table>
<!-- </form> -->

</div>
<%
	if (loginManager.getUserType() == SUserType.ADMIN) {
		for (SQueryEngine queryEngine : loginManager.getService().getAllQueryEngines(true)) {
%>
<div class="tabbertab" title="<%=queryEngine.getName()%>" id="<%=queryEngine.getOid() %>">
Examples: <%
	for (String key : loginManager.getService().getQueryEngineExampleKeys(queryEngine.getOid())) {
%><a href="#" qeid="<%=queryEngine.getOid() %>" key="<%=key%>" class="examplebutton"><%=key%></a> <%
	}
%>
<textarea cols="93" rows="16" id="code">
</textarea>
<div style="float: right">
	<span id="ajaxloader">
	<span id="ajaxloadertext"></span> <img src="images/ajax-loader.gif"/>
	</span>
	<button class="querybutton" qeid="<%=queryEngine.getOid()%>">Query</button>
</div>
<textarea cols="93" rows="16" id="console">
</textarea>
</div>
<%
		}
%>
<script>
	$(function(){
		$(".examplebutton").click(function(event){
			event.preventDefault();
			$.ajax({
				url: '<%=request.getContextPath() %>/compile?action=example&key=' + $(event.target).attr("key") + '&qeid=' + $(event.target).attr("qeid"),
				success: function(data) {
					$("#code").val(data);
				}
			});
		});
		$("#ajaxloader").hide();
		$("#advancedquerylink").click(function(){
			$("#simplequery").hide();
			$("#advancedquery").show();
			$("#advancedquerylink").addClass("linkactive");
			$("#simplequerylink").removeClass("linkactive");
		});
		$("#simplequerylink").click(function(){
			$("#advancedquery").hide();
			$("#simplequery").show();
			$("#simplequerylink").addClass("linkactive");
			$("#advancedquerylink").removeClass("linkactive");
		});
		$("#simplequerylink").addClass("linkactive");
		$("#simplequery").show();
		$("#advancedquery").hide();
		
		function success(data) {
			$("#ajaxloader").hide();
			$("#console").val("");
			if (data.compileErrors != null && data.compileErrors.length > 0) {
				$("#console").addClass("compile_error");
				for (var i=0; i<data.compileErrors.length; i++) {
					$("#console").val(data.compileErrors[i]);
				}
			} else {
				if (data.output != null) {
					$("#console").removeClass("compile_error");
					$("#console").val(data.output);
				}
			}
		}
		
		$(".querybutton").click(function(){
			$("#ajaxloader").show();
			$("#ajaxloadertext").text("Querying...");
			$.ajax({
				url: "<%=request.getContextPath() %>/compile",
				type: "POST",
				dataType: "json",
				data: {roid:<%=roid%>, action: "query", code:$("#code").val(), qeid: $(this).attr("qeid")},
				success: success
			});
		});

		$("textarea").keydown(checkTab);
		 
		// Set desired tab- defaults to four space softtab
		var tab = "    ";
		       
		function checkTab(evt) {
		    var t = evt.target;
		    var ss = t.selectionStart;
		    var se = t.selectionEnd;
		 
		    // Tab key - insert tab expansion
		    if (evt.keyCode == 9) {
		        evt.preventDefault();
		               
		        // Special case of multi line selection
		        if (ss != se && t.value.slice(ss,se).indexOf("\n") != -1) {
		            // In case selection was not of entire lines (e.g. selection begins in the middle of a line)
		            // we ought to tab at the beginning as well as at the start of every following line.
		            var pre = t.value.slice(0,ss);
		            var sel = t.value.slice(ss,se).replace(/\n/g,"\n"+tab);
		            var post = t.value.slice(se,t.value.length);
		            t.value = pre.concat(tab).concat(sel).concat(post);
		                   
		            t.selectionStart = ss + tab.length;
		            t.selectionEnd = se + tab.length;
		        }
		               
		        // "Normal" case (no selection or selection on one line only)
		        else {
		            t.value = t.value.slice(0,ss).concat(tab).concat(t.value.slice(ss,t.value.length));
		            if (ss == se) {
		                t.selectionStart = t.selectionEnd = ss + tab.length;
		            }
		            else {
		                t.selectionStart = ss + tab.length;
		                t.selectionEnd = se + tab.length;
		            }
		        }
		    }
		           
		    // Backspace key - delete preceding tab expansion, if exists
		   else if (evt.keyCode==8 && t.value.slice(ss - 4,ss) == tab) {
		        evt.preventDefault();
		               
		        t.value = t.value.slice(0,ss - 4).concat(t.value.slice(ss,t.value.length));
		        t.selectionStart = t.selectionEnd = ss - tab.length;
		    }
		           
		    // Delete key - delete following tab expansion, if exists
		    else if (evt.keyCode==46 && t.value.slice(se,se + 4) == tab) {
		        evt.preventDefault();
		             
		        t.value = t.value.slice(0,ss).concat(t.value.slice(ss + 4,t.value.length));
		        t.selectionStart = t.selectionEnd = ss;
		    }
		    // Left/right arrow keys - move across the tab in one go
		    else if (evt.keyCode == 37 && t.value.slice(ss - 4,ss) == tab) {
		        evt.preventDefault();
		        t.selectionStart = t.selectionEnd = ss - 4;
		    }
		    else if (evt.keyCode == 39 && t.value.slice(ss,ss + 4) == tab) {
		        evt.preventDefault();
		        t.selectionStart = t.selectionEnd = ss + 4;
		    }
		}
	});
</script>
<%
	}
%>
</div>
<script>
	$(function(){
		$("#queryoidsbutton").click(function(event){
			event.preventDefault();
			var downloadframe = $(this).parents(".downloadframe");
			var roid = downloadframe.find('input[name="roid"]');
			var oids = downloadframe.find('input[name="oids"]');
			var params = {
				downloadType: "oids",
				allowCheckout: false,
				roid: roid.val(),
				oids: [oids.val()]
			};
			showDownloadCheckoutPopup("download.jsp?data=" + JSON.stringify(params));
		});
		$("#queryguidsbutton").click(function(event){
			event.preventDefault();
			var downloadframe = $(this).parents(".downloadframe");
			var roid = downloadframe.find('input[name="roid"]');
			var guids = downloadframe.find('input[name="guids"]');
			var params = {
				downloadType: "guids",
				allowCheckout: false,
				roid: roid.val(),
				guids: [guids.val()]
			};
			showDownloadCheckoutPopup("download.jsp?data=" + JSON.stringify(params));
		});
		$("#queryclassesbutton").click(function(event){
			event.preventDefault();
			var downloadframe = $(this).parents(".downloadframe");
			var roid = downloadframe.find('input[name="roid"]');
			var ifcClass = downloadframe.find("#cid");
			var params = {
				downloadType: "classes",
				allowCheckout: false,
				roid: roid.val(),
				classes: [ifcClass.val()]
			};
			showDownloadCheckoutPopup("download.jsp?data=" + JSON.stringify(params));
		});
	});
</script>